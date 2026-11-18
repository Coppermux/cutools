package com.coppermux.cutools.core.css

import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomInspectorListener
import com.coppermux.cutools.core.dom.DomInspectionState
import com.coppermux.cutools.core.dom.DomMutationState
import com.coppermux.cutools.core.dom.DomNode
import com.coppermux.cutools.core.dom.DomOperationResult
import com.coppermux.cutools.core.dom.DomSearchState
import com.coppermux.cutools.core.dom.DomState
import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.error.CssException
import com.coppermux.cutools.core.error.DomException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Listener for CSS inspection state changes. */
interface CssInspectorListener {
    fun onCssStateChanged(state: CssInspectionState)
    fun onCssCapabilityChanged(capability: CssCapabilityStatus)
    fun onError(error: Throwable)
}

/**
 * Orchestrates genuine Chrome-backed CSS style inspection for the node selected
 * in the [DomInspector].
 *
 * Phase 3A scope:
 * - Enables the `CSS` domain on the active session (`CSS.enable`).
 * - Tracks stylesheet metainformation via `CSS.styleSheetAdded` / `Removed`.
 * - On DOM selection change: invalidates the previous node's CSS state, requests
 *   `CSS.getMatchedStylesForNode` for the new node, and publishes a
 *   [CssInspectionState] (loading / loaded / empty / failed / unsupported).
 * - Associates every response with the requested node + generation, so a stale
 *   or older response can never overwrite current state.
 * - Reuses the Phase 1 [CommandDispatcher] and [CdpEventRouter]; no second
 *   WebSocket or connection is created.
 *
 * Threading: all state mutation is serialized on a single-thread executor.
 * Listeners are invoked on that executor thread; UI callers must marshal onto
 * the Android UI thread.
 *
 * Chrome is the source of truth: CuTools never locally computes cascade results
 * or computed styles.
 */
class CssInspector(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
    private val domInspector: DomInspector,
) : DomInspectorListener {

    /** Read-only CSS inspection state for the currently selected node. */
    @Volatile
    var inspectionState: CssInspectionState = CssInspectionState.empty()
        private set

    /** CSS domain availability on the active target. */
    @Volatile
    var capability: CssCapabilityStatus = CssCapabilityStatus.UNKNOWN
        private set

    /** Last capability error, if any. */
    @Volatile
    var capabilityError: Throwable? = null
        private set

    private val listeners = CopyOnWriteArrayList<CssInspectorListener>()
    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()
    private val stylesheets = ConcurrentHashMap<String, CssStyleSheet>()

    /** Guards CSS.enable / stylesheet events across session transitions. */
    private val generation = AtomicInteger(0)

    /** Guards per-inspection requests so newer selections invalidate older ones. */
    private val inspectionGeneration = AtomicInteger(0)

    /** Guards against duplicate concurrent pseudo-state operations. */
    private val pseudoOpInProgress = AtomicBoolean(false)

    /** Guards against duplicate concurrent overlay operations. */
    private val overlayOpInProgress = AtomicBoolean(false)

    /** Whether the Overlay domain has been enabled on the current session. */
    @Volatile
    private var overlayEnabled = false

    /** Overlay domain availability (independent of structured grid/flex inspection). */
    @Volatile
    private var overlayCapability: CssCapabilityStatus = CssCapabilityStatus.UNKNOWN

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cutools-css").apply { isDaemon = true }
    }

    /** Schedules inspection watchdogs so a request never stays in LOADING forever. */
    private val watchdogExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "cutools-css-watchdog").apply { isDaemon = true }
        }

    private companion object {
        /** Longer than the dispatcher's per-command timeout so the command timeout normally resolves first. */
        const val INSPECTION_WATCHDOG_MS = 6_000L
        const val MAX_INSPECTION_ATTEMPTS = 3
    }

    init {
        domInspector.addListener(this)
        registerCssSubscriptions()
        executor.execute {
            // The DOM inspector may already be READY (e.g. the inspector was
            // created after attachment) — enable the CSS domain in that case.
            if (domInspector.state() == DomState.READY) {
                onDomReady()
            }
        }
    }

    fun addListener(listener: CssInspectorListener) { listeners.add(listener) }
    fun removeListener(listener: CssInspectorListener) { listeners.remove(listener) }

    /** Explicitly requests CSS inspection (matched styles + computed + box model) for [nodeId]. */
    fun inspectNode(nodeId: Int) {
        executor.execute { requestInspection(nodeId) }
    }

    /** Re-requests CSS inspection for the currently selected node. */
    fun refresh() {
        executor.execute {
            val selected = domInspector.selectedNode()?.nodeId
            if (selected != null) requestInspection(selected)
        }
    }

    /**
     * Forces or removes [pseudoClass] on [nodeId] via the real Chrome
     * `CSS.forcePseudoState` command.
     *
     * The confirmed forced set is updated ONLY after Chrome accepts the
     * operation; a rejected operation never looks successful. After success the
     * inspection is refreshed so displayed styles reflect the browser's state.
     */
    fun setPseudoClass(
        nodeId: Int,
        pseudoClass: CssPseudoClass,
        enabled: Boolean,
        callback: (DomOperationResult<Unit>) -> Unit,
    ) {
        executor.execute { runPseudoOperation(nodeId, pseudoClass, enabled, callback) }
    }

    /** Clears CSS inspection state (used when no target is attached). */
    fun clear() {
        executor.execute {
            generation.incrementAndGet()
            inspectionGeneration.incrementAndGet()
            pseudoOpInProgress.set(false)
            overlayOpInProgress.set(false)
            stylesheets.clear()
            updateState(CssInspectionState.empty())
        }
    }

    /** Shuts down the internal executor and unsubscribes from events. */
    fun shutdown() {
        clearCssSubscriptions()
        executor.shutdownNow()
        watchdogExecutor.shutdownNow()
    }

    // ================================================================
    // DomInspectorListener
    // ================================================================

    override fun onSelectionChanged(node: DomNode?) {
        executor.execute {
            pseudoOpInProgress.set(false)
            overlayOpInProgress.set(false)
            val prevNodeId = inspectionState.nodeId
            val prevPseudo = inspectionState.pseudoState
            val prevOverlay = inspectionState.overlayState
            if (node == null) {
                // Selection cleared — invalidate the previous node's CSS state.
                if (prevNodeId != null && prevPseudo.forcedClasses.isNotEmpty()) {
                    clearForcedPseudoInChrome(prevNodeId)
                }
                if (prevNodeId != null && prevOverlay.isActive) {
                    clearOverlayInChrome(prevOverlay.type)
                }
                generation.incrementAndGet()
                inspectionGeneration.incrementAndGet()
                updateState(CssInspectionState.empty())
            } else {
                // Node changed: clear browser-side pseudo/overlay state on the
                // previous node (never transfer it to the new node).
                if (prevNodeId != null && prevNodeId != node.nodeId && prevPseudo.forcedClasses.isNotEmpty()) {
                    clearForcedPseudoInChrome(prevNodeId)
                }
                if (prevNodeId != null && prevNodeId != node.nodeId && prevOverlay.isActive) {
                    clearOverlayInChrome(prevOverlay.type)
                }
                requestInspection(node.nodeId)
            }
        }
    }

    override fun onStateChanged(state: DomState, error: Throwable?) {
        executor.execute {
            when (state) {
                DomState.READY -> onDomReady()
                DomState.INACTIVE, DomState.ERROR -> onDomInactive()
                else -> { /* LOADING etc. — nothing to do */ }
            }
        }
    }

    /**
     * Enables the CSS domain and (re)inspects the current selection. Runs on the
     * CSS executor.
     *
     * NOTE on stylesheet lifecycle (verified against Chrome): `CSS.enable`
     * replays `styleSheetAdded` only for stylesheet ids the CSS agent has not yet
     * reported; re-enabling does NOT replay already-known sheets. Therefore the
     * tracked-stylesheets map is intentionally NOT cleared here — it is
     * incrementally maintained by `styleSheetAdded` / `styleSheetRemoved` events
     * (on document replacement Chrome emits `styleSheetRemoved` for the old
     * sheets). The map is only cleared on session teardown ([onDomInactive]).
     */
    private fun onDomReady() {
        // Invalidate any stale in-flight inspection from the previous document.
        generation.incrementAndGet()
        enableCss()
        val selected = domInspector.selectedNode()?.nodeId
        if (selected != null) requestInspection(selected)
    }

    /** Invalidates all CSS state on detach / connection loss. Runs on the CSS executor. */
    private fun onDomInactive() {
        generation.incrementAndGet()
        inspectionGeneration.incrementAndGet()
        pseudoOpInProgress.set(false)
        overlayOpInProgress.set(false)
        overlayEnabled = false
        overlayCapability = CssCapabilityStatus.UNKNOWN
        stylesheets.clear()
        capability = CssCapabilityStatus.UNKNOWN
        capabilityError = null
        updateState(CssInspectionState.empty())
        notifyCapabilityChanged()
    }

    override fun onError(error: Throwable) {
        notifyError(error)
    }

    override fun onDocumentLoaded(document: DomNode?) {}
    override fun onNodesChanged(changedNodeIds: Set<Int>) {}
    override fun onInspectionStateChanged(state: DomInspectionState) {}
    override fun onSearchStateChanged(state: DomSearchState) {}
    override fun onMutationStateChanged(state: DomMutationState) {}

    // ================================================================
    // CSS domain enablement
    // ================================================================

    private fun enableCss() {
        val sess = domInspector.session ?: return
        val gen = generation.get()
        if (!isCurrentLifecycle(gen)) return
        dispatcher.sendCommand("CSS.enable", sessionId = sess.sessionId) { result ->
            executor.execute {
                if (!isCurrentLifecycle(gen)) return@execute
                when (result) {
                    is CommandResult.Success -> {
                        capability = CssCapabilityStatus.SUPPORTED
                        capabilityError = null
                        notifyCapabilityChanged()
                        // Refresh the current state's stylesheet snapshot.
                        updateState(inspectionState.copy(capability = capability, stylesheets = currentStylesheets()))
                    }
                    is CommandResult.Failure -> {
                        capability = classifyCapabilityFailure(result.error)
                        capabilityError = result.error
                        notifyCapabilityChanged()
                        val selected = domInspector.selectedNode()?.nodeId
                        if (selected != null) {
                            val unsupported = CssInspectionState(
                                nodeId = selected,
                                phase = CssInspectionPhase.UNSUPPORTED,
                                capability = capability,
                                error = result.error,
                                computedStylePhase = CssInspectionPhase.UNSUPPORTED,
                                computedStyleError = result.error,
                                layerPhase = CssInspectionPhase.UNSUPPORTED,
                                layerError = result.error,
                                boxModelPhase = CssInspectionPhase.UNSUPPORTED,
                                boxModelError = result.error,
                                pseudoState = CssPseudoState(
                                    phase = CssPseudoStatePhase.UNSUPPORTED,
                                    capability = capability,
                                ),
                            )
                            updateState(unsupported)
                        }
                    }
                }
            }
        }
    }

    private fun classifyCapabilityFailure(error: Throwable): CssCapabilityStatus =
        if (isMethodNotFound(error)) CssCapabilityStatus.UNSUPPORTED else CssCapabilityStatus.FAILED

    // ================================================================
    // Style inspection
    // ================================================================

    /**
     * Requests the full CSS inspection bundle for [nodeId]: matched styles
     * (`CSS.getMatchedStylesForNode`), computed style (`CSS.getComputedStyleForNode`),
     * cascade layers (`CSS.getLayersForNode`), and the box model (`DOM.getBoxModel`).
     *
     * All four responses are associated with the requested node + generation; a
     * stale or older response can never overwrite the current selection's state.
     */
    private fun requestInspection(nodeId: Int, attempt: Int = 0) {
        val inspGen = inspectionGeneration.incrementAndGet()
        val lifeGen = generation.get()
        val sess = domInspector.session
        if (sess == null || domInspector.state() != DomState.READY) {
            updateState(
                CssInspectionState(
                    nodeId = nodeId,
                    phase = CssInspectionPhase.IDLE,
                    capability = capability,
                )
            )
            return
        }
        val cssBlocked = capability == CssCapabilityStatus.UNSUPPORTED || capability == CssCapabilityStatus.FAILED
        val cssPhase = if (cssBlocked) CssInspectionPhase.UNSUPPORTED else CssInspectionPhase.LOADING
        // Pseudo-state and overlay state are preserved across refreshes of the
        // SAME node but reset for a new selection (never transferred between nodes).
        val previousPseudo = if (inspectionState.nodeId == nodeId) inspectionState.pseudoState else CssPseudoState.empty()
        val previousOverlay = if (inspectionState.nodeId == nodeId) inspectionState.overlayState else CssOverlayState.empty()
        val pseudoForState = if (cssBlocked) {
            previousPseudo.copy(phase = CssPseudoStatePhase.UNSUPPORTED, capability = CssCapabilityStatus.UNSUPPORTED)
        } else {
            previousPseudo
        }
        updateState(
            CssInspectionState(
                nodeId = nodeId,
                phase = cssPhase,
                capability = capability,
                error = if (cssBlocked) capabilityError else null,
                computedStylePhase = cssPhase,
                computedStyleError = if (cssBlocked) capabilityError else null,
                layerPhase = cssPhase,
                layerError = if (cssBlocked) capabilityError else null,
                boxModelPhase = CssInspectionPhase.LOADING,
                boxModelError = null,
                stylesheets = currentStylesheets(),
                pseudoState = pseudoForState,
                overlayState = previousOverlay,
                layoutPhase = CssInspectionPhase.LOADING,
                layoutError = null,
                containerPhase = CssInspectionPhase.LOADING,
                containerError = null,
            )
        )
        if (!cssBlocked) {
            fetchMatchedStyles(nodeId, inspGen, lifeGen)
            fetchComputedStyle(nodeId, inspGen, lifeGen)
            fetchLayers(nodeId, inspGen, lifeGen)
        }
        fetchBoxModel(nodeId, inspGen, lifeGen)
        fetchLayout(nodeId, inspGen, lifeGen)
        fetchContainer(nodeId, inspGen, lifeGen)
        scheduleInspectionWatchdog(nodeId, inspGen, lifeGen, attempt)
    }

    /**
     * Guarantees the inspection never stays in LOADING indefinitely (e.g. when a
     * session is slow under load and neither a response nor the dispatcher
     * command timeout is applied). Bounded retries, then an honest FAILED state.
     */
    private fun scheduleInspectionWatchdog(nodeId: Int, inspGen: Int, lifeGen: Int, attempt: Int) {
        watchdogExecutor.schedule({
            executor.execute {
                if (!isCurrent(inspGen, lifeGen)) return@execute
                if (inspectionState.nodeId != nodeId) return@execute
                if (inspectionState.phase != CssInspectionPhase.LOADING) return@execute
                if (attempt >= MAX_INSPECTION_ATTEMPTS) {
                    val timeout = CssException("CSS inspection timed out after $MAX_INSPECTION_ATTEMPTS attempts")
                    updateState(
                        inspectionState.copy(
                            phase = CssInspectionPhase.FAILED,
                            error = timeout,
                        )
                    )
                    notifyError(timeout)
                    return@execute
                }
                requestInspection(nodeId, attempt + 1)
            }
        }, INSPECTION_WATCHDOG_MS, TimeUnit.MILLISECONDS)
    }

    /** Applies a response only when it belongs to the current inspection of [nodeId]. */
    private fun applyCurrent(nodeId: Int, inspGen: Int, lifeGen: Int, block: () -> Unit) {
        if (!isCurrent(inspGen, lifeGen)) return
        if (inspectionState.nodeId != nodeId) return
        block()
    }

    private fun fetchMatchedStyles(nodeId: Int, inspGen: Int, lifeGen: Int) {
        val sess = domInspector.session ?: return
        dispatcher.sendCommand(
            "CSS.getMatchedStylesForNode",
            mapOf("nodeId" to nodeId),
            sessionId = sess.sessionId,
        ) { result ->
            executor.execute {
                applyCurrent(nodeId, inspGen, lifeGen) {
                    when (result) {
                        is CommandResult.Success -> {
                            val styles = try {
                                CssParser.parseNodeStyles(result.response.result, nodeId)
                            } catch (e: Exception) {
                                updateState(
                                    inspectionState.copy(
                                        phase = CssInspectionPhase.FAILED,
                                        error = CssException("Failed to parse CSS response: ${e.message}", cause = e),
                                    )
                                )
                                notifyError(e)
                                return@applyCurrent
                            }
                            val resolved = CssCascade.resolve(styles)
                            val phase = if (resolved.isEmpty) CssInspectionPhase.EMPTY else CssInspectionPhase.LOADED
                            updateState(
                                inspectionState.copy(
                                    phase = phase,
                                    styles = resolved,
                                    stylesheets = currentStylesheets(),
                                    error = null,
                                )
                            )
                        }
                        is CommandResult.Failure -> {
                            updateState(
                                inspectionState.copy(
                                    phase = CssInspectionPhase.FAILED,
                                    error = result.error,
                                )
                            )
                            notifyError(result.error)
                        }
                    }
                }
            }
        }
    }

    private fun fetchComputedStyle(nodeId: Int, inspGen: Int, lifeGen: Int) {
        val sess = domInspector.session ?: return
        dispatcher.sendCommand(
            "CSS.getComputedStyleForNode",
            mapOf("nodeId" to nodeId),
            sessionId = sess.sessionId,
        ) { result ->
            executor.execute {
                applyCurrent(nodeId, inspGen, lifeGen) {
                    when (result) {
                        is CommandResult.Success -> {
                            val props = CssParser.parseComputedStyleResponse(result.response.result)
                            val phase = if (props.isEmpty()) CssInspectionPhase.EMPTY else CssInspectionPhase.LOADED
                            updateState(
                                inspectionState.copy(
                                    computedStylePhase = phase,
                                    computedStyle = props,
                                    computedStyleError = null,
                                )
                            )
                        }
                        is CommandResult.Failure -> {
                            updateState(
                                inspectionState.copy(
                                    computedStylePhase = CssInspectionPhase.FAILED,
                                    computedStyle = null,
                                    computedStyleError = result.error,
                                )
                            )
                            notifyError(result.error)
                        }
                    }
                }
            }
        }
    }

    private fun fetchLayers(nodeId: Int, inspGen: Int, lifeGen: Int) {
        val sess = domInspector.session ?: return
        dispatcher.sendCommand(
            "CSS.getLayersForNode",
            mapOf("nodeId" to nodeId),
            sessionId = sess.sessionId,
        ) { result ->
            executor.execute {
                applyCurrent(nodeId, inspGen, lifeGen) {
                    when (result) {
                        is CommandResult.Success -> {
                            val layer = CssParser.parseLayersResponse(result.response.result)
                            val phase = if (layer == null) CssInspectionPhase.EMPTY else CssInspectionPhase.LOADED
                            updateState(
                                inspectionState.copy(
                                    layerPhase = phase,
                                    rootLayer = layer,
                                    layerError = null,
                                )
                            )
                        }
                        is CommandResult.Failure -> {
                            val phase = if (isMethodNotFound(result.error)) {
                                CssInspectionPhase.UNSUPPORTED
                            } else {
                                CssInspectionPhase.FAILED
                            }
                            updateState(
                                inspectionState.copy(
                                    layerPhase = phase,
                                    rootLayer = null,
                                    layerError = result.error,
                                )
                            )
                            notifyError(result.error)
                        }
                    }
                }
            }
        }
    }

    private fun fetchBoxModel(nodeId: Int, inspGen: Int, lifeGen: Int) {
        val sess = domInspector.session ?: return
        dispatcher.sendCommand(
            "DOM.getBoxModel",
            mapOf("nodeId" to nodeId),
            sessionId = sess.sessionId,
        ) { result ->
            executor.execute {
                applyCurrent(nodeId, inspGen, lifeGen) {
                    when (result) {
                        is CommandResult.Success -> {
                            val model = CssParser.parseBoxModel(result.response.result)
                            val phase = if (model.isEmpty) CssInspectionPhase.EMPTY else CssInspectionPhase.LOADED
                            updateState(
                                inspectionState.copy(
                                    boxModelPhase = phase,
                                    boxModel = model,
                                    boxModelError = null,
                                )
                            )
                        }
                        is CommandResult.Failure -> {
                            val phase = if (isMethodNotFound(result.error)) {
                                CssInspectionPhase.UNSUPPORTED
                            } else {
                                CssInspectionPhase.FAILED
                            }
                            updateState(
                                inspectionState.copy(
                                    boxModelPhase = phase,
                                    boxModel = null,
                                    boxModelError = result.error,
                                )
                            )
                            notifyError(result.error)
                        }
                    }
                }
            }
        }
    }

    private fun isMethodNotFound(error: Throwable): Boolean {
        // The CommandDispatcher embeds the protocol error code ("-32601") in the
        // message; that is the authoritative "method not found" signal. Readable
        // messages like "Node not found" must NOT be misclassified as unsupported.
        return error.message.orEmpty().contains("-32601")
    }

    // ================================================================
    // Layout / container-query inspection (Phase 3D)
    // ================================================================

    /**
     * Fetches node-level layout geometry from Chrome (`DOM.getContentQuads`,
     * `DOM.getRelayoutBoundary`). Grid/Flexbox structured data is NOT exposed by
     * this CDP version (Overlay APIs only), so those capabilities are represented
     * as unsupported in the inspection state.
     */
    private fun fetchLayout(nodeId: Int, inspGen: Int, lifeGen: Int) {
        val sess = domInspector.session ?: return
        dispatcher.sendCommand(
            "DOM.getContentQuads",
            mapOf("nodeId" to nodeId),
            sessionId = sess.sessionId,
        ) { result ->
            executor.execute {
                applyCurrent(nodeId, inspGen, lifeGen) {
                    when (result) {
                        is CommandResult.Success -> {
                            val quadsRaw = (result.response.result as? Map<*, *>)?.get("quads")
                            val quads = CssParser.parseContentQuads(quadsRaw)
                            val boundary = inspectionState.layoutInfo?.relayoutBoundaryNodeId
                            val phase = if (quads.isEmpty() && boundary == null) {
                                CssInspectionPhase.EMPTY
                            } else {
                                CssInspectionPhase.LOADED
                            }
                            updateState(
                                inspectionState.copy(
                                    layoutPhase = phase,
                                    layoutInfo = CssLayoutInfo(nodeId, quads, boundary),
                                    layoutError = null,
                                )
                            )
                        }
                        is CommandResult.Failure -> {
                            val phase = if (isMethodNotFound(result.error)) {
                                CssInspectionPhase.UNSUPPORTED
                            } else {
                                CssInspectionPhase.FAILED
                            }
                            updateState(
                                inspectionState.copy(
                                    layoutPhase = phase,
                                    layoutInfo = null,
                                    layoutError = result.error,
                                )
                            )
                            notifyError(result.error)
                        }
                    }
                }
            }
        }
        dispatcher.sendCommand(
            "DOM.getRelayoutBoundary",
            mapOf("nodeId" to nodeId),
            sessionId = sess.sessionId,
        ) { result ->
            executor.execute {
                applyCurrent(nodeId, inspGen, lifeGen) {
                    if (result is CommandResult.Success) {
                        val boundary = CssParser.parseRelayoutBoundary(result.response.result)
                        updateState(
                            inspectionState.copy(
                                layoutInfo = (inspectionState.layoutInfo ?: CssLayoutInfo(nodeId))
                                    .copy(relayoutBoundaryNodeId = boundary),
                                layoutPhase = if (inspectionState.layoutPhase == CssInspectionPhase.EMPTY && boundary != null) {
                                    CssInspectionPhase.LOADED
                                } else {
                                    inspectionState.layoutPhase
                                },
                            )
                        )
                    } else if (result is CommandResult.Failure) {
                        notifyError(result.error)
                    }
                }
            }
        }
    }

    /**
     * Fetches container-query relationships for the node (`DOM.getContainerForNode`,
     * `DOM.getQueryingDescendantsForContainer`).
     */
    private fun fetchContainer(nodeId: Int, inspGen: Int, lifeGen: Int) {
        val sess = domInspector.session ?: return
        dispatcher.sendCommand(
            "DOM.getContainerForNode",
            mapOf("nodeId" to nodeId),
            sessionId = sess.sessionId,
        ) { result ->
            executor.execute {
                applyCurrent(nodeId, inspGen, lifeGen) {
                    when (result) {
                        is CommandResult.Success -> {
                            val containerId = CssParser.parseContainerForNode(result.response.result)
                            val phase = if (containerId == null) {
                                CssInspectionPhase.EMPTY
                            } else {
                                CssInspectionPhase.LOADED
                            }
                            updateState(
                                inspectionState.copy(
                                    containerPhase = phase,
                                    containerInfo = CssContainerInfo(
                                        nodeId = nodeId,
                                        containerNodeId = containerId,
                                        queryingDescendants = inspectionState.containerInfo?.queryingDescendants.orEmpty(),
                                    ),
                                    containerError = null,
                                )
                            )
                        }
                        is CommandResult.Failure -> {
                            val phase = if (isMethodNotFound(result.error)) {
                                CssInspectionPhase.UNSUPPORTED
                            } else {
                                CssInspectionPhase.FAILED
                            }
                            updateState(
                                inspectionState.copy(
                                    containerPhase = phase,
                                    containerInfo = null,
                                    containerError = result.error,
                                )
                            )
                            notifyError(result.error)
                        }
                    }
                }
            }
        }
        dispatcher.sendCommand(
            "DOM.getQueryingDescendantsForContainer",
            mapOf("nodeId" to nodeId),
            sessionId = sess.sessionId,
        ) { result ->
            executor.execute {
                applyCurrent(nodeId, inspGen, lifeGen) {
                    if (result is CommandResult.Success) {
                        val descendants = CssParser.parseQueryingDescendants(result.response.result)
                        val info = CssContainerInfo(
                            nodeId = nodeId,
                            containerNodeId = inspectionState.containerInfo?.containerNodeId,
                            queryingDescendants = descendants,
                        )
                        updateState(
                            inspectionState.copy(
                                containerInfo = info,
                                containerPhase = if (inspectionState.containerPhase == CssInspectionPhase.EMPTY && descendants.isNotEmpty()) {
                                    CssInspectionPhase.LOADED
                                } else {
                                    inspectionState.containerPhase
                                },
                            )
                        )
                    } else if (result is CommandResult.Failure) {
                        notifyError(result.error)
                    }
                }
            }
        }
    }

    // ================================================================
    // Pseudo-class forcing (Phase 3C)
    // ================================================================

    private fun runPseudoOperation(
        nodeId: Int,
        pseudoClass: CssPseudoClass,
        enabled: Boolean,
        callback: (DomOperationResult<Unit>) -> Unit,
    ) {
        val sess = domInspector.session
        if (sess == null || domInspector.state() != DomState.READY) {
            callback(DomOperationResult.Failure(ConnectionException("Not attached to a target")))
            return
        }
        val currentPseudo = inspectionState.pseudoState
        if (currentPseudo.nodeId != null && currentPseudo.nodeId != nodeId) {
            callback(DomOperationResult.Failure(DomException("Selection changed; pseudo-state request rejected")))
            return
        }
        if (capability == CssCapabilityStatus.UNSUPPORTED || capability == CssCapabilityStatus.FAILED) {
            updateState(
                inspectionState.copy(
                    pseudoState = currentPseudo.copy(
                        nodeId = nodeId,
                        phase = CssPseudoStatePhase.UNSUPPORTED,
                        capability = capability,
                        error = capabilityError,
                    )
                )
            )
            callback(DomOperationResult.Failure(ConnectionException("CSS domain unavailable")))
            return
        }
        if (!pseudoOpInProgress.compareAndSet(false, true)) {
            callback(DomOperationResult.Failure(DomException("A pseudo-state operation is already in progress")))
            return
        }
        val confirmed = currentPseudo.forcedClasses
        val requested = if (enabled) confirmed + pseudoClass else confirmed - pseudoClass
        val phase = if (enabled) CssPseudoStatePhase.APPLYING else CssPseudoStatePhase.REMOVING
        // Reflect the pending request without marking it confirmed.
        updateState(
            inspectionState.copy(
                pseudoState = currentPseudo.copy(
                    nodeId = nodeId,
                    phase = phase,
                    error = null,
                )
            )
        )
        val inspGen = inspectionGeneration.get()
        val lifeGen = generation.get()
        val forcedList = requested.map { it.cdpValue }.filter { it.isNotEmpty() }
        dispatcher.sendCommand(
            "CSS.forcePseudoState",
            mapOf("nodeId" to nodeId, "forcedPseudoClasses" to forcedList),
            sessionId = sess.sessionId,
        ) { result ->
            executor.execute {
                if (!isCurrent(inspGen, lifeGen) || inspectionState.nodeId != nodeId) {
                    pseudoOpInProgress.set(false)
                    callback(DomOperationResult.Failure(DomException("Stale pseudo-state response")))
                    return@execute
                }
                when (result) {
                    is CommandResult.Success -> {
                        pseudoOpInProgress.set(false)
                        updateState(
                            inspectionState.copy(
                                pseudoState = CssPseudoState(
                                    nodeId = nodeId,
                                    forcedClasses = requested,
                                    phase = CssPseudoStatePhase.APPLIED,
                                    capability = CssCapabilityStatus.SUPPORTED,
                                    error = null,
                                )
                            )
                        )
                        // Refresh inspection so the displayed styles reflect the
                        // browser's new forced state (no CSS event fires for forcing).
                        requestInspection(nodeId)
                        callback(DomOperationResult.Success(Unit))
                    }
                    is CommandResult.Failure -> {
                        pseudoOpInProgress.set(false)
                        val cap = classifyPseudoCapability(result.error)
                        val newPhase = if (cap == CssCapabilityStatus.UNSUPPORTED) {
                            CssPseudoStatePhase.UNSUPPORTED
                        } else {
                            CssPseudoStatePhase.FAILED
                        }
                        // forcedClasses stays at the CONFIRMED set — never pretend a
                        // rejected operation succeeded.
                        updateState(
                            inspectionState.copy(
                                pseudoState = currentPseudo.copy(
                                    nodeId = nodeId,
                                    phase = newPhase,
                                    capability = cap,
                                    error = result.error,
                                )
                            )
                        )
                        notifyError(result.error)
                        callback(DomOperationResult.Failure(result.error))
                    }
                }
            }
        }
    }

    private fun classifyPseudoCapability(error: Throwable): CssCapabilityStatus =
        if (isMethodNotFound(error)) CssCapabilityStatus.UNSUPPORTED else CssCapabilityStatus.FAILED

    /** Best-effort clear of forced pseudo-classes for a node in Chrome. */
    private fun clearForcedPseudoInChrome(nodeId: Int) {
        val sess = domInspector.session ?: return
        dispatcher.sendCommand(
            "CSS.forcePseudoState",
            mapOf("nodeId" to nodeId, "forcedPseudoClasses" to emptyList<String>()),
            sessionId = sess.sessionId,
        ) { }
    }

    // ================================================================
    // Layout overlays (Phase 3E)
    // ================================================================

    /**
     * Shows or hides [type] overlay on [nodeId] via the real `Overlay` CDP domain.
     * The overlay is only marked ACTIVE after Chrome confirms; a rejected
     * operation never looks active.
     */
    fun setOverlay(
        type: CssOverlayType,
        nodeId: Int,
        enabled: Boolean,
        callback: (DomOperationResult<Unit>) -> Unit,
    ) {
        executor.execute { runOverlayOperation(type, nodeId, enabled, callback) }
    }

    private fun runOverlayOperation(
        type: CssOverlayType,
        nodeId: Int,
        enabled: Boolean,
        callback: (DomOperationResult<Unit>) -> Unit,
    ) {
        val sess = domInspector.session
        if (sess == null || domInspector.state() != DomState.READY) {
            callback(DomOperationResult.Failure(ConnectionException("Not attached to a target")))
            return
        }
        if (overlayCapability == CssCapabilityStatus.UNSUPPORTED || overlayCapability == CssCapabilityStatus.FAILED) {
            updateState(
                inspectionState.copy(
                    overlayState = CssOverlayState(
                        type = type,
                        phase = CssOverlayPhase.UNSUPPORTED,
                        capability = overlayCapability,
                    )
                )
            )
            callback(DomOperationResult.Failure(ConnectionException("Overlay domain unavailable")))
            return
        }
        if (!overlayOpInProgress.compareAndSet(false, true)) {
            callback(DomOperationResult.Failure(DomException("An overlay operation is already in progress")))
            return
        }
        if (!overlayEnabled) {
            enableOverlayDomain(type, nodeId, enabled, callback)
            return
        }
        sendShowOverlay(type, nodeId, enabled, callback)
    }

    private fun enableOverlayDomain(
        type: CssOverlayType,
        nodeId: Int,
        enabled: Boolean,
        callback: (DomOperationResult<Unit>) -> Unit,
    ) {
        val sess = domInspector.session ?: run {
            overlayOpInProgress.set(false)
            callback(DomOperationResult.Failure(ConnectionException("Not attached to a target")))
            return
        }
        val lifeGen = generation.get()
        dispatcher.sendCommand("Overlay.enable", sessionId = sess.sessionId) { result ->
            executor.execute {
                if (generation.get() != lifeGen) {
                    overlayOpInProgress.set(false)
                    callback(DomOperationResult.Failure(DomException("Stale overlay response")))
                    return@execute
                }
                when (result) {
                    is CommandResult.Success -> {
                        overlayEnabled = true
                        overlayCapability = CssCapabilityStatus.SUPPORTED
                        sendShowOverlay(type, nodeId, enabled, callback)
                    }
                    is CommandResult.Failure -> {
                        overlayOpInProgress.set(false)
                        overlayCapability = classifyPseudoCapability(result.error)
                        val phase = if (overlayCapability == CssCapabilityStatus.UNSUPPORTED) {
                            CssOverlayPhase.UNSUPPORTED
                        } else {
                            CssOverlayPhase.FAILED
                        }
                        updateState(
                            inspectionState.copy(
                                overlayState = CssOverlayState(
                                    type = type,
                                    phase = phase,
                                    capability = overlayCapability,
                                    error = result.error,
                                )
                            )
                        )
                        notifyError(result.error)
                        callback(DomOperationResult.Failure(result.error))
                    }
                }
            }
        }
    }

    private fun sendShowOverlay(
        type: CssOverlayType,
        nodeId: Int,
        enabled: Boolean,
        callback: (DomOperationResult<Unit>) -> Unit,
    ) {
        val sess = domInspector.session ?: run {
            overlayOpInProgress.set(false)
            callback(DomOperationResult.Failure(ConnectionException("Not attached to a target")))
            return
        }
        val lifeGen = generation.get()
        val (method, key, cfg) = overlayCommand(type)
        val params = if (enabled) {
            mapOf(key to listOf(mapOf("nodeId" to nodeId) + cfg))
        } else {
            mapOf(key to emptyList<Any>())
        }
        updateState(
            inspectionState.copy(
                overlayState = CssOverlayState(
                    type = type,
                    phase = CssOverlayPhase.REQUESTED,
                    capability = overlayCapability,
                    error = null,
                )
            )
        )
        dispatcher.sendCommand(method, params, sessionId = sess.sessionId) { result ->
            executor.execute {
                if (generation.get() != lifeGen || inspectionState.nodeId != nodeId) {
                    overlayOpInProgress.set(false)
                    callback(DomOperationResult.Failure(DomException("Stale overlay response")))
                    return@execute
                }
                overlayOpInProgress.set(false)
                when (result) {
                    is CommandResult.Success -> {
                        updateState(
                            inspectionState.copy(
                                overlayState = CssOverlayState(
                                    type = type,
                                    phase = if (enabled) CssOverlayPhase.ACTIVE else CssOverlayPhase.DISABLED,
                                    capability = CssCapabilityStatus.SUPPORTED,
                                    error = null,
                                )
                            )
                        )
                        callback(DomOperationResult.Success(Unit))
                    }
                    is CommandResult.Failure -> {
                        val cap = classifyPseudoCapability(result.error)
                        val phase = if (cap == CssCapabilityStatus.UNSUPPORTED) {
                            CssOverlayPhase.UNSUPPORTED
                        } else {
                            CssOverlayPhase.FAILED
                        }
                        updateState(
                            inspectionState.copy(
                                overlayState = CssOverlayState(
                                    type = type,
                                    phase = phase,
                                    capability = cap,
                                    error = result.error,
                                )
                            )
                        )
                        notifyError(result.error)
                        callback(DomOperationResult.Failure(result.error))
                    }
                }
            }
        }
    }

    private fun clearOverlayInChrome(type: CssOverlayType?) {
        val t = type ?: return
        val sess = domInspector.session ?: return
        val (method, key, _) = overlayCommand(t)
        dispatcher.sendCommand(method, mapOf(key to emptyList<Any>()), sessionId = sess.sessionId) { }
    }

    private fun overlayCommand(type: CssOverlayType): Triple<String, String, Map<String, Any?>> = when (type) {
        CssOverlayType.GRID -> Triple(
            "Overlay.setShowGridOverlays",
            "gridNodeHighlightConfigs",
            mapOf("gridHighlightConfig" to mapOf<String, Any?>()),
        )
        CssOverlayType.FLEX -> Triple(
            "Overlay.setShowFlexOverlays",
            "flexNodeHighlightConfigs",
            mapOf("flexContainerHighlightConfig" to mapOf<String, Any?>()),
        )
        CssOverlayType.CONTAINER_QUERY -> Triple(
            "Overlay.setShowContainerQueryOverlays",
            "containerQueryHighlightConfigs",
            mapOf("containerQueryContainerHighlightConfig" to mapOf<String, Any?>()),
        )
    }

    // ================================================================
    // Stylesheet event tracking
    // ================================================================

    private fun registerCssSubscriptions() {
        subscriptions += eventRouter.subscribe("CSS.styleSheetAdded") { event ->
            onCssEvent(event) { params ->
                val header = params["header"] as? Map<*, *> ?: return@onCssEvent
                val sheet = try {
                    CssParser.parseStyleSheetHeader(header)
                } catch (e: Exception) {
                    return@onCssEvent
                }
                stylesheets[sheet.styleSheetId] = sheet
                updateState(inspectionState.copy(stylesheets = currentStylesheets()))
            }
        }
        subscriptions += eventRouter.subscribe("CSS.styleSheetRemoved") { event ->
            onCssEvent(event) { params ->
                val id = params["styleSheetId"] as? String ?: return@onCssEvent
                stylesheets.remove(id)
                updateState(inspectionState.copy(stylesheets = currentStylesheets()))
            }
        }
        subscriptions += eventRouter.subscribe("CSS.styleSheetChanged") { event ->
            onCssEvent(event) {
                // A tracked stylesheet changed — re-request the full inspection for
                // the current selection. Never re-request while a load is in flight
                // so a burst of events cannot starve the phase.
                val selected = domInspector.selectedNode()?.nodeId
                if (selected != null && inspectionState.nodeId == selected &&
                    inspectionState.phase != CssInspectionPhase.LOADING
                ) {
                    requestInspection(selected)
                }
            }
        }
        subscriptions += eventRouter.subscribe("CSS.computedStyleUpdated") { event ->
            onCssEvent(event) {
                // Browser-side computed styles changed (e.g. pseudo-states forced by
                // other means, animations) — refresh the current selection so
                // displayed styles match the browser. Never re-request while a load
                // is already in flight, otherwise a burst of events keeps
                // invalidating fresh requests and the phase never reaches LOADED.
                val selected = domInspector.selectedNode()?.nodeId
                if (selected != null && inspectionState.nodeId == selected &&
                    inspectionState.phase != CssInspectionPhase.LOADING
                ) {
                    requestInspection(selected)
                }
            }
        }
    }

    /** Runs a CSS event handler on the executor if the event belongs to the active session. */
    private fun onCssEvent(event: Event, handler: (Map<*, *>) -> Unit) {
        executor.execute {
            if (event.sessionId != domInspector.session?.sessionId) return@execute
            val params = event.params
            if (params !is Map<*, *>) return@execute
            try {
                handler(params)
            } catch (e: Exception) {
                notifyError(CssException("Failed to apply CSS event ${event.method}: ${e.message}", cause = e))
            }
        }
    }

    private fun clearCssSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }

    // ================================================================
    // State / notification helpers
    // ================================================================

    private fun currentStylesheets(): List<CssStyleSheet> =
        stylesheets.values.sortedBy { it.styleSheetId }.toList()

    private fun updateState(newState: CssInspectionState) {
        inspectionState = newState
        listeners.forEach { it.onCssStateChanged(newState) }
    }

    private fun notifyCapabilityChanged() {
        listeners.forEach { it.onCssCapabilityChanged(capability) }
    }

    private fun notifyError(error: Throwable) {
        listeners.forEach { it.onError(error) }
    }

    private fun isCurrent(inspGen: Int, lifeGen: Int): Boolean =
        inspectionGeneration.get() == inspGen && generation.get() == lifeGen

    private fun isCurrentLifecycle(gen: Int): Boolean = generation.get() == gen
}