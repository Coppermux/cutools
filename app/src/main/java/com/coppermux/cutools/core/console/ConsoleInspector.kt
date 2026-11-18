package com.coppermux.cutools.core.console

import android.util.Log
import com.coppermux.cutools.core.dom.DomInspectionState
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomInspectorListener
import com.coppermux.cutools.core.dom.DomMutationState
import com.coppermux.cutools.core.dom.DomNode
import com.coppermux.cutools.core.dom.DomSearchState
import com.coppermux.cutools.core.dom.DomState
import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.error.ConsoleException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.runtime.EvaluationOutcome
import com.coppermux.cutools.core.runtime.EvaluationRequest
import com.coppermux.cutools.core.runtime.EvaluationResultKind
import com.coppermux.cutools.core.runtime.EvaluationResultMapper
import com.coppermux.cutools.core.runtime.ExecutionEngine
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "CuTools/ConsoleInspector"

private fun logTrace(tag: String, message: String) {
    try {
        Log.d(tag, message)
    } catch (_: Throwable) {
        // Fallback for JVM unit tests where android.util.Log is not mocked
    }
    println("[$tag] $message")
}

/** Listener for Console state / error changes. */
interface ConsoleInspectorListener {
    fun onConsoleStateChanged(state: ConsoleState)
    fun onError(error: Throwable)
}

/**
 * Orchestrates the Console tool for the currently attached page target.
 *
 * Phase 4A — Console event reception:
 * - Binds to an attached CDP session, enabling the `Runtime` and `Console`
 *   domains (`Runtime.enable`, `Console.enable`).
 * - Receives genuine Chrome console events (`Runtime.consoleAPICalled`,
 *   `Runtime.exceptionThrown`, `Console.messageAdded`).
 * - Publishes explicit [ConsoleState] with the received [ConsoleEntry] models.
 *
 * Phase 4B — evaluation integration:
 * - Records CuTools evaluation outcomes as EVALUATION_RESULT entries.
 *
 * Phase 4C — developer utilities:
 * - Tracks the $0 selected-element reference from the existing Elements
 *   ([DomInspector]) selection and resolves it to a genuine Chrome
 *   RemoteObject via CDP `DOM.resolveNode`.
 * - Runs the querySelector / querySelectorAll helpers as genuine page-context
 *   evaluation (CDP-backed), reusing the Phase 4B execution infrastructure.
 * - Owns the command history, input, and history navigation (CuTools-only).
 * - Exposes the [ConsoleFeatureOrigin] classification of each feature.
 *
 * Every entry is tied to its session/target, events/outcomes are filtered by
 * the active session, and state is cleared on target switch / detach /
 * connection loss so a previous target's data never becomes the new target's.
 *
 * All Chrome communication reuses DevTools Core; no second transport, WebSocket,
 * or dispatcher is created.
 *
 * Threading: Console state is mutated on a single-thread executor; listeners
 * are invoked on that executor thread, so UI callers must marshal onto the
 * Android UI thread.
 */
class ConsoleInspector(
    private var dispatcher: CommandDispatcher,
    private var eventRouter: CdpEventRouter,
    private val domInspector: DomInspector,
    private val executionEngine: ExecutionEngine,
    private val dispatcherProvider: (() -> CommandDispatcher)? = null,
    private val eventRouterProvider: (() -> CdpEventRouter)? = null,
    private val executor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cutools-console").apply { isDaemon = true }
    },
) : DomInspectorListener {

    /** Stable diagnostic instance identity for tracing across lifecycle and submit. */
    val diagnosticInstanceId: String = "ConsoleInspector@" + Integer.toHexString(System.identityHashCode(this))

    /** Active command dispatcher (provider-backed or direct instance). */
    val activeDispatcher: CommandDispatcher
        get() = dispatcherProvider?.invoke() ?: dispatcher

    /** Active CDP event router (provider-backed or direct instance). */
    val activeEventRouter: CdpEventRouter
        get() = eventRouterProvider?.invoke() ?: eventRouter

    /** Flushes any queued work on the internal executor (for deterministic synchronization). */
    fun drain(timeoutMs: Long = 3000): Boolean {
        if (executor.isShutdown) return true
        return try {
            executor.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Rebinds to fresh core transport components across reconnects. */
    fun rebind(newDispatcher: CommandDispatcher, newEventRouter: CdpEventRouter) {
        dispatcher = newDispatcher
        eventRouter = newEventRouter
        if (session != null) {
            clearSubscriptions()
            registerSubscriptions()
        }
    }

    /** Read-only Console state. */
    @Volatile
    var state: ConsoleState = ConsoleState.empty()
        private set

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    private val listeners = CopyOnWriteArrayList<ConsoleInspectorListener>()
    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    /** Guards $0 resolution against rapid selection changes. */
    private val selectionGeneration = AtomicInteger(0)

    /** Local arrival-order sequence for displayed entries. */
    private val nextEntryId = AtomicLong(0)

    @Volatile
    private var runtimeCapability = ConsoleCapabilityStatus.UNKNOWN
    @Volatile
    private var consoleDomainCapability = ConsoleCapabilityStatus.UNKNOWN
    @Volatile
    private var capabilityError: Throwable? = null

    private var enableRuntimePending = true
    private var enableConsolePending = true

    init {
        domInspector.addListener(this)
        executor.execute { syncSelectionFromDom() }
    }

    fun addListener(listener: ConsoleInspectorListener) { listeners.add(listener) }
    fun removeListener(listener: ConsoleInspectorListener) { listeners.remove(listener) }

    // ================================================================
    // Lifecycle
    // ================================================================

    /**
     * Binds to an attached session and initializes Console event reception.
     * Any prior target's entries and developer-tool state are discarded; only
     * events for this session are accepted from this point on.
     */
    fun bindSession(newSession: SessionInfo) {
        val prevSession = session
        logTrace(
            TAG,
            "bindSession [instance=$diagnosticInstanceId] targetId=${newSession.targetId} sessionId=${newSession.sessionId} previousTargetId=${prevSession?.targetId} previousSessionId=${prevSession?.sessionId}"
        )
        val gen = generation.incrementAndGet()
        session = newSession
        nextEntryId.set(0)
        runtimeCapability = ConsoleCapabilityStatus.UNKNOWN
        consoleDomainCapability = ConsoleCapabilityStatus.UNKNOWN
        capabilityError = null
        enableRuntimePending = true
        enableConsolePending = true
        clearSubscriptions()
        registerSubscriptions()
        // CuTools-wide presentation preferences (search/filter) survive a
        // target switch; all target-scoped state is reinitialized.
        val presentation = state.preservingPresentation()
        updateState(
            ConsoleState(
                entries = emptyList(),
                sessionId = newSession.sessionId,
                targetId = newSession.targetId,
                phase = ConsoleLifecyclePhase.ENABLING,
                capability = ConsoleCapabilityStatus.UNKNOWN,
                runtimeCapability = ConsoleCapabilityStatus.UNKNOWN,
                consoleDomainCapability = ConsoleCapabilityStatus.UNKNOWN,
                searchQuery = presentation.searchQuery,
                severityFilter = presentation.severityFilter,
            )
        )
        enableRuntime(gen)
        enableConsole(gen)
        executor.execute { syncSelectionFromDom() }
    }

    /** Detaches, clearing target-scoped state while preserving CuTools-wide presentation state. */
    fun detach() {
        val prevSession = session
        logTrace(
            TAG,
            "detach [instance=$diagnosticInstanceId] previousTargetId=${prevSession?.targetId} previousSessionId=${prevSession?.sessionId}"
        )
        generation.incrementAndGet()
        selectionGeneration.incrementAndGet()
        session = null
        enableRuntimePending = true
        enableConsolePending = true
        clearSubscriptions()
        updateState(state.preservingPresentation())
    }

    /**
     * Clears console entries and input while preserving the active session,
     * domain capabilities, and presentation preferences (search/filter).
     */
    fun clear() {
        val gen = generation.incrementAndGet()
        executor.execute {
            if (!isCurrent(gen)) return@execute
            updateState(
                state.copy(
                    entries = emptyList(),
                    input = "",
                )
            )
        }
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    /** Shuts down the internal executor and unsubscribes from events. */
    fun shutdown() {
        domInspector.removeListener(this)
        clearSubscriptions()
        executor.shutdownNow()
    }

    // ================================================================
    // Domain enablement
    // ================================================================

    private fun enableRuntime(gen: Int) {
        val sess = session ?: return
        activeDispatcher.sendCommand("Runtime.enable", sessionId = sess.sessionId) { result ->
            executor.execute {
                if (!isCurrent(gen)) return@execute
                enableRuntimePending = false
                when (result) {
                    is CommandResult.Success -> {
                        runtimeCapability = ConsoleCapabilityStatus.SUPPORTED
                        maybeCompleteEnable()
                    }
                    is CommandResult.Failure -> {
                        runtimeCapability = classifyCapabilityFailure(result.error)
                        capabilityError = result.error
                        finishEnableFailure(result.error)
                    }
                }
            }
        }
    }

    private fun enableConsole(gen: Int) {
        val sess = session ?: return
        activeDispatcher.sendCommand("Console.enable", sessionId = sess.sessionId) { result ->
            executor.execute {
                if (!isCurrent(gen)) return@execute
                enableConsolePending = false
                when (result) {
                    is CommandResult.Success -> {
                        consoleDomainCapability = ConsoleCapabilityStatus.SUPPORTED
                        maybeCompleteEnable()
                    }
                    is CommandResult.Failure -> {
                        consoleDomainCapability = classifyCapabilityFailure(result.error)
                        capabilityError = result.error
                        maybeCompleteEnable()
                    }
                }
            }
        }
    }

    /**
     * Resolves the overall capability once both enable commands have settled.
     * The Runtime domain is required for Console event reception; the Console
     * domain is a supplementary source, so a failed/unsupported Console domain
     * does not disable a healthy Runtime-backed Console.
     */
    private fun maybeCompleteEnable() {
        if (enableRuntimePending || enableConsolePending) return
        when {
            runtimeCapability == ConsoleCapabilityStatus.SUPPORTED -> {
                updateState(
                    state.copy(
                        phase = ConsoleLifecyclePhase.READY,
                        capability = ConsoleCapabilityStatus.SUPPORTED,
                        runtimeCapability = runtimeCapability,
                        consoleDomainCapability = consoleDomainCapability,
                        capabilityError = null,
                    )
                )
            }
            runtimeCapability == ConsoleCapabilityStatus.UNSUPPORTED -> {
                val error = capabilityError
                    ?: ConsoleException("The Runtime domain is not available for this target")
                updateState(
                    state.copy(
                        phase = ConsoleLifecyclePhase.UNSUPPORTED,
                        capability = ConsoleCapabilityStatus.UNSUPPORTED,
                        runtimeCapability = runtimeCapability,
                        consoleDomainCapability = consoleDomainCapability,
                        capabilityError = error,
                    )
                )
                notifyError(error)
            }
            else -> {
                val error = capabilityError
                    ?: ConsoleException("Failed to enable the Console on this target")
                updateState(
                    state.copy(
                        phase = ConsoleLifecyclePhase.FAILED,
                        capability = ConsoleCapabilityStatus.FAILED,
                        runtimeCapability = runtimeCapability,
                        consoleDomainCapability = consoleDomainCapability,
                        capabilityError = error,
                    )
                )
                notifyError(error)
            }
        }
    }

    /** Fails fast when the REQUIRED Runtime domain could not be enabled. */
    private fun finishEnableFailure(error: Throwable) {
        val failedPhase = if (runtimeCapability == ConsoleCapabilityStatus.UNSUPPORTED) {
            ConsoleLifecyclePhase.UNSUPPORTED
        } else {
            ConsoleLifecyclePhase.FAILED
        }
        val failedCapability = if (runtimeCapability == ConsoleCapabilityStatus.UNSUPPORTED) {
            ConsoleCapabilityStatus.UNSUPPORTED
        } else {
            ConsoleCapabilityStatus.FAILED
        }
        updateState(
            state.copy(
                phase = failedPhase,
                capability = failedCapability,
                runtimeCapability = runtimeCapability,
                consoleDomainCapability = consoleDomainCapability,
                capabilityError = error,
                error = error,
            )
        )
        notifyError(error)
    }

    private fun classifyCapabilityFailure(error: Throwable): ConsoleCapabilityStatus =
        if (isMethodNotFound(error)) ConsoleCapabilityStatus.UNSUPPORTED else ConsoleCapabilityStatus.FAILED

    private fun isMethodNotFound(error: Throwable): Boolean =
        error.message.orEmpty().contains("-32601")

    private fun isStaleNodeError(error: Throwable): Boolean {
        val msg = error.message.orEmpty()
        return msg.contains("find node", ignoreCase = true) ||
            msg.contains("could not find", ignoreCase = true) ||
            msg.contains("node id", ignoreCase = true)
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        val router = activeEventRouter
        subscriptions += router.subscribe("Runtime.consoleAPICalled") { event -> onConsoleEvent(event) }
        subscriptions += router.subscribe("Runtime.exceptionThrown") { event -> onConsoleEvent(event) }
        subscriptions += router.subscribe("Console.messageAdded") { event -> onConsoleEvent(event) }
    }

    /** Applies a console event on the executor if it belongs to the active session. */
    private fun onConsoleEvent(event: Event) {
        val eventGen = generation.get()
        executor.execute {
            if (!isCurrent(eventGen)) return@execute
            val active = session ?: return@execute
            if (event.sessionId != active.sessionId) return@execute
            try {
                when (event.method) {
                    "Runtime.consoleAPICalled" -> handleConsoleApiCalled(event, active)
                    "Runtime.exceptionThrown" -> handleExceptionThrown(event, active)
                    "Console.messageAdded" -> handleMessageAdded(event, active)
                }
            } catch (e: Exception) {
                notifyError(ConsoleException("Failed to apply console event ${event.method}: ${e.message}", cause = e))
            }
        }
    }

    private fun handleConsoleApiCalled(event: Event, active: SessionInfo) {
        ConsoleEventMapper.mapConsoleApiCalled(event.params, active.sessionId, active.targetId)?.let { appendEntry(it) }
    }

    private fun handleExceptionThrown(event: Event, active: SessionInfo) {
        ConsoleEventMapper.mapExceptionThrown(event.params, active.sessionId, active.targetId)?.let { appendEntry(it) }
    }

    private fun handleMessageAdded(event: Event, active: SessionInfo) {
        // A page console API call surfaces BOTH as Console.messageAdded (source
        // "console-api") and as Runtime.consoleAPICalled. The Runtime event is the
        // canonical structured representation (type, args, timestamp, stack), so
        // the Console-domain projection is skipped to avoid duplicate entries.
        val params = event.params
        if (params is Map<*, *>) {
            val message = params["message"] as? Map<*, *>
            if (message != null && message["source"] == "console-api") return
        }
        ConsoleEventMapper.mapMessageAdded(event.params, active.sessionId, active.targetId)?.let { appendEntry(it) }
    }

    private fun appendEntry(mapped: ConsoleEntry) {
        val entry = if (mapped.id == 0L) mapped.copy(id = nextEntryId.incrementAndGet()) else mapped
        updateState(state.copy(entries = appendBounded(state.entries, entry)))
    }

    // ================================================================
    // Evaluation integration (Phase 4B)
    // ================================================================

    /**
     * Records a CuTools JavaScript evaluation (or query-helper) outcome as an
     * [ConsoleEntryKind.EVALUATION_RESULT] entry in Console state.
     *
     * This integrates execution results with the existing Console state while
     * keeping their origin explicit: the entry is NOT a genuine Chrome console
     * event and no Chrome console event is fabricated for it. [origin] and
     * [helperLabel] classify the feature (native Chrome / CDP-backed /
     * CuTools-only). Outcomes that do not belong to the currently active
     * session are dropped, so an evaluation can never be attributed to a
     * different target.
     */
    fun recordEvaluation(
        outcome: EvaluationOutcome,
        origin: ConsoleFeatureOrigin = ConsoleFeatureOrigin.NATIVE_CHROME,
        helperLabel: String? = null,
    ) {
        executor.execute {
            val active = session ?: return@execute
            if (outcome.sessionId != active.sessionId) return@execute
            val severity = when (outcome.result) {
                is EvaluationResultKind.Success -> ConsoleSeverity.LOG
                is EvaluationResultKind.Exception -> ConsoleSeverity.ERROR
                is EvaluationResultKind.ProtocolError -> ConsoleSeverity.ERROR
            }
            val entry = ConsoleEntry(
                id = nextEntryId.incrementAndGet(),
                kind = ConsoleEntryKind.EVALUATION_RESULT,
                severity = severity,
                type = ConsoleMessageType.UNKNOWN,
                text = outcome.displayText,
                timestamp = null,
                sessionId = active.sessionId,
                targetId = active.targetId,
                evaluation = outcome,
                origin = origin,
                helperLabel = helperLabel,
            )
            logTrace(
                TAG,
                "recordEvaluation [instance=$diagnosticInstanceId] entryId=${entry.id} text='${entry.text}' severity=${entry.severity}"
            )
            appendEntry(entry)
        }
    }

    // ================================================================
    // Phase 4C — $0 selected-element reference
    // ================================================================

    /** The currently selected element (from the Elements tool), or null. */
    val selectedElement: SelectedElementReference? get() = state.selectedElement

    /**
     * Resolves the currently selected element to a genuine Chrome RemoteObject
     * via CDP `DOM.resolveNode`. $0 is a CuTools developer convenience backed
     * by the existing Elements selection — never a fabricated/local DOM object.
     */
    fun resolveSelectedElement() {
        executor.execute {
            val node = domInspector.selectedNode() ?: run {
                updateState(state.copy(selectedElement = null))
                return@execute
            }
            val sess = session ?: run {
                updateState(state.copy(selectedElement = SelectedElementReference(
                    nodeId = node.nodeId,
                    backendNodeId = node.backendNodeId,
                    label = node.displayLabel,
                    sessionId = "",
                    targetId = null,
                    phase = SelectedElementPhase.RESOLVING,
                )))
                return@execute
            }
            resolveSelectedElement(node, sess)
        }
    }

    private fun syncSelectionFromDom() {
        val node = domInspector.selectedNode() ?: return
        val sess = session ?: return
        handleSelectionChanged(node)
    }

    override fun onSelectionChanged(node: DomNode?) {
        executor.execute { handleSelectionChanged(node) }
    }

    private fun handleSelectionChanged(node: DomNode?) {
        selectionGeneration.incrementAndGet()
        if (node == null) {
            updateState(state.copy(selectedElement = null))
            return
        }
        val sess = session
        if (sess == null) {
            updateState(state.copy(selectedElement = SelectedElementReference(
                nodeId = node.nodeId,
                backendNodeId = node.backendNodeId,
                label = node.displayLabel,
                sessionId = "",
                targetId = null,
                phase = SelectedElementPhase.RESOLVING,
            )))
            return
        }
        resolveSelectedElement(node, sess)
    }

    private fun resolveSelectedElement(node: DomNode, sess: SessionInfo) {
        val gen = generation.get()
        val selGen = selectionGeneration.get()
        updateState(state.copy(selectedElement = SelectedElementReference(
            nodeId = node.nodeId,
            backendNodeId = node.backendNodeId,
            label = node.displayLabel,
            sessionId = sess.sessionId,
            targetId = sess.targetId,
            phase = SelectedElementPhase.RESOLVING,
        )))
        activeDispatcher.sendCommand("DOM.resolveNode", mapOf("nodeId" to node.nodeId), sessionId = sess.sessionId) { result ->
            executor.execute {
                if (!isCurrent(gen) || selectionGeneration.get() != selGen) return@execute
                if (session?.sessionId != sess.sessionId) return@execute
                val current = state.selectedElement
                if (current == null || current.nodeId != node.nodeId) return@execute
                when (result) {
                    is CommandResult.Success -> {
                        val remoteObject = EvaluationResultMapper.parseRemoteObject(
                            (result.response.result as? Map<*, *>)?.get("object")
                        )
                        updateState(state.copy(selectedElement = current.copy(
                            phase = SelectedElementPhase.LOADED,
                            remoteObject = remoteObject,
                            error = null,
                        )))
                    }
                    is CommandResult.Failure -> {
                        val phase = when {
                            isMethodNotFound(result.error) -> SelectedElementPhase.UNSUPPORTED
                            isStaleNodeError(result.error) -> SelectedElementPhase.STALE
                            else -> SelectedElementPhase.FAILED
                        }
                        updateState(state.copy(selectedElement = current.copy(phase = phase, error = result.error)))
                        notifyError(result.error)
                    }
                }
            }
        }
    }

    // ================================================================
    // Phase 4C — query helpers (CDP-backed)
    // ================================================================

    /** Runs `document.querySelector(selector)` against the real page context. */
    fun runQuerySelector(selector: String) = executor.execute { runQuery(QuerySelector, selector) }

    /** Runs `document.querySelectorAll(selector)` against the real page context. */
    fun runQuerySelectorAll(selector: String) = executor.execute { runQuery(QuerySelectorAll, selector) }

    private fun runQuery(helper: ConsoleQueryHelper, selector: String) {
        val trimmed = selector.trim()
        val sess = session
        if (sess == null) {
            val err = ConnectionException("Not attached to a target")
            updateState(state.copy(queryHelperState = QueryHelperState(activeHelper = helper, selector = trimmed, running = false)))
            notifyError(err)
            return
        }
        if (trimmed.isEmpty()) {
            updateState(state.copy(queryHelperState = QueryHelperState.empty()))
            return
        }
        val gen = generation.get()
        val expression = helper.buildExpression(trimmed)
        updateState(state.copy(queryHelperState = QueryHelperState(activeHelper = helper, selector = trimmed, running = true)))
        executionEngine.evaluate(EvaluationRequest(expression)) { outcome ->
            executor.execute {
                if (!isCurrent(gen)) return@execute
                if (session?.sessionId != outcome.sessionId) return@execute
                updateState(state.copy(queryHelperState = QueryHelperState(
                    activeHelper = helper,
                    selector = trimmed,
                    running = false,
                    lastOutcome = outcome,
                )))
                recordEvaluation(outcome, origin = helper.origin, helperLabel = helper.displayLabel)
            }
        }
    }

    // ================================================================
    // Phase 4C — command history / input (CuTools-only)
    // ================================================================

    /** Syncs the current input text into Console state (CuTools-only state). */
    fun setInput(text: String) {
        executor.execute {
            val current = state
            // Editing while positioned on a history entry returns to live input.
            val history = if (current.history.position != -1 && text != current.history.displayInput()) {
                current.history.copy(position = -1, draft = null)
            } else {
                current.history
            }
            if (current.input != text || current.history != history) {
                updateState(current.copy(input = text, history = history))
            }
        }
    }

    /**
     * Submits the current input for genuine Chrome execution: records the
     * expression in history (CuTools-only state) and evaluates it via the
     * execution engine with Chrome's command-line API enabled (so `$0` and the
     * native console helpers resolve). History recording never executes the
     * expression itself.
     */
    fun submitInput(explicitExpression: String? = null) {
        executor.execute {
            val current = state
            val gen = generation.get()
            val rawExpression = explicitExpression ?: current.input
            val expression = rawExpression.trim()
            val sess = session
            logTrace(
                TAG,
                "submitInput [instance=$diagnosticInstanceId] expression='$expression' sessionIsNull=${sess == null} targetId=${sess?.targetId} sessionId=${sess?.sessionId}"
            )
            if (expression.isEmpty()) return@execute
            if (sess == null) {
                notifyError(ConnectionException("Attach a target before evaluating in the Java Console."))
                return@execute
            }
            val history = current.history.record(expression)
            val commandEntry = ConsoleEntry(
                id = nextEntryId.incrementAndGet(),
                kind = ConsoleEntryKind.COMMAND,
                severity = ConsoleSeverity.LOG,
                type = ConsoleMessageType.UNKNOWN,
                text = rawExpression,
                timestamp = null,
                sessionId = sess.sessionId,
                targetId = sess.targetId,
                origin = ConsoleFeatureOrigin.NATIVE_CHROME,
            )
            updateState(current.copy(
                history = history,
                input = "",
                entries = appendBounded(current.entries, commandEntry),
            ))
            val request = EvaluationRequest(
                expression = expression,
                includeCommandLineAPI = true,
                awaitPromise = true,
            )
            executionEngine.evaluate(request) { outcome ->
                executor.execute {
                    if (!isCurrent(gen)) return@execute
                    logTrace(
                        TAG,
                        "recordEvaluation from evaluate [instance=$diagnosticInstanceId] id=${outcome.id} expression='${outcome.request.expression}' result=${outcome.result} displayText='${outcome.displayText}'"
                    )
                    if (session?.sessionId != outcome.sessionId) return@execute
                    recordEvaluation(outcome, origin = ConsoleFeatureOrigin.NATIVE_CHROME)
                }
            }
        }
    }

    /** Navigates backward through command history (changes input only). */
    fun historyBack() {
        executor.execute {
            val current = state
            val history = current.history.back(current.input)
            updateState(current.copy(input = history.displayInput(), history = history))
        }
    }

    /** Navigates forward through command history (changes input only). */
    fun historyForward() {
        executor.execute {
            val current = state
            val history = current.history.forward(current.input)
            updateState(current.copy(input = history.displayInput(), history = history))
        }
    }

    // ================================================================
    // Phase 4D — search & severity filtering (CuTools presentation state)
    // ================================================================
    //
    // Search and severity filtering are CuTools presentation/state conveniences
    // over the structured Console entries. They operate purely on local state,
    // never trigger browser commands, never mutate the underlying entry
    // collection, and never affect Chrome's console history.

    /** Sets the console search query (presentation state only). */
    fun setSearchQuery(query: String) {
        executor.execute {
            if (state.searchQuery != query) {
                updateState(state.copy(searchQuery = query))
            }
        }
    }

    /** Clears the console search query. */
    fun clearSearch() {
        setSearchQuery("")
    }

    /** Replaces the severity filter (presentation state only). */
    fun setSeverityFilter(filter: ConsoleSeverityFilter) {
        executor.execute {
            if (state.severityFilter != filter) {
                updateState(state.copy(severityFilter = filter))
            }
        }
    }

    /** Includes/excludes [severity] in the active severity filter. */
    fun setSeverityEnabled(severity: ConsoleSeverity, enabled: Boolean) {
        executor.execute {
            updateState(state.copy(severityFilter = state.severityFilter.withSeverity(severity, enabled)))
        }
    }

    /** Includes/excludes runtime-exception entries (independent of severity). */
    fun setRuntimeExceptionsVisible(enabled: Boolean) {
        executor.execute {
            updateState(state.copy(severityFilter = state.severityFilter.withRuntimeExceptions(enabled)))
        }
    }

    /** Clears all severity filtering (back to showing everything). */
    fun clearSeverityFilter() {
        executor.execute {
            if (state.severityFilter.isActive) {
                updateState(state.copy(severityFilter = ConsoleSeverityFilter()))
            }
        }
    }

    // ================================================================
    // DomInspectorListener
    // ================================================================

    override fun onStateChanged(state: DomState, error: Throwable?) {}
    override fun onDocumentLoaded(document: DomNode?) {}
    override fun onNodesChanged(changedNodeIds: Set<Int>) {}
    override fun onInspectionStateChanged(state: DomInspectionState) {}
    override fun onSearchStateChanged(state: DomSearchState) {}
    override fun onMutationStateChanged(state: DomMutationState) {}
    override fun onError(error: Throwable) {}

    // ================================================================
    // State / notification helpers
    // ================================================================

    private fun updateState(newState: ConsoleState) {
        state = newState
        listeners.forEach { it.onConsoleStateChanged(newState) }
    }

    private fun notifyError(error: Throwable) {
        listeners.forEach { it.onError(error) }
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }

    private fun isCurrent(gen: Int): Boolean = generation.get() == gen

    companion object {
        /** Maximum number of Console entries retained in memory (PERF-01). */
        const val MAX_ENTRIES: Int = 1000

        /**
         * Appends [entry] to [entries], discarding the oldest entries when capacity
         * exceeds [maxCapacity]. Retains newest entries and preserves chronological order.
         */
        internal fun appendBounded(
            entries: List<ConsoleEntry>,
            entry: ConsoleEntry,
            maxCapacity: Int = MAX_ENTRIES,
        ): List<ConsoleEntry> {
            return if (entries.size >= maxCapacity) {
                val bounded = ArrayList<ConsoleEntry>(maxCapacity)
                bounded.addAll(entries.subList(entries.size - maxCapacity + 1, entries.size))
                bounded.add(entry)
                bounded
            } else {
                val bounded = ArrayList<ConsoleEntry>(entries.size + 1)
                bounded.addAll(entries)
                bounded.add(entry)
                bounded
            }
        }
    }
}
