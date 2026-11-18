package com.coppermux.cutools.core.network

import com.coppermux.cutools.core.browser.FaultInterceptorEngine
import com.coppermux.cutools.core.browser.FaultRule
import com.coppermux.cutools.core.error.NetworkException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Listener for Fetch interception state / error changes. */
interface FetchInterceptionListener {
    fun onFetchStateChanged(state: FetchInterceptionState)
    fun onError(error: Throwable)
}

/**
 * Orchestrates active request interception over the CDP `Fetch` domain for the
 * currently attached page target.
 *
 * Phase 5C — Fetch domain interception & mocking engine:
 * - Binds to an attached CDP session, enabling `Fetch.enable` with configurable
 *   [RequestPattern]s and auth-challenge handling.
 * - Subscribes to `Fetch.requestPaused` events, routes each paused request
 *   through the in-memory [MockResponseRuleManager], and reacts with
 *   `Fetch.fulfillRequest` (mock response), `Fetch.failRequest` (network
 *   failure), or `Fetch.continueRequest` (pass-through).
 * - Exposes direct action methods ([fulfillRequest], [failRequest],
 *   [continueRequest], [continueWithAuth]) whose outcomes are reported through
 *   an explicit [FetchActionResult] — never an uncaught exception.
 * - Tracks the set of paused request IDs for the active session so a
 *   requestId from a previous session (after target switch / detach /
 *   reconnect) is rejected as [FetchActionResult.UNKNOWN_REQUEST] instead of
 *   acting on a stale, un-paused request.
 *
 * Every interception hook is tied to its session/target and cleared on target
 * switch / detach / connection loss, so hooks from a previous target never leak
 * into a new one and no pending decision promise is left dangling.
 *
 * Threading: interception state is mutated on a single-thread executor;
 * listeners are invoked on that executor thread, so UI callers must marshal
 * onto the Android UI thread.
 */
class FetchInterceptionEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
    val ruleManager: MockResponseRuleManager = MockResponseRuleManager(),
    private val faultInterceptorEngine: FaultInterceptorEngine? = null,
) {

    /** Read-only Fetch interception state. */
    @Volatile
    var state: FetchInterceptionState = FetchInterceptionState()
        private set

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    private val listeners = CopyOnWriteArrayList<FetchInterceptionListener>()
    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    /**
     * Paused request IDs for the active session, mapped to the session that
     * paused them. Cleared on detach/re-bind so stale IDs are rejected.
     */
    private val pausedRequestIds = ConcurrentHashMap<String, String>()

    @Volatile
    private var capability = FetchCapabilityStatus.UNKNOWN
    @Volatile
    private var capabilityError: Throwable? = null

    private var enabledPatterns: List<RequestPattern> = emptyList()
    private var authRequestsEnabled = false
    private var enablePending = true

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cutools-fetch").apply { isDaemon = true }
    }

    init {
        faultInterceptorEngine?.addListener {
            syncFaultRules()
        }
    }

    fun addListener(listener: FetchInterceptionListener) { listeners.add(listener) }
    fun removeListener(listener: FetchInterceptionListener) { listeners.remove(listener) }

    // ================================================================
    // Lifecycle
    // ================================================================

    /**
     * Synchronizes active drop-connection rules from [faultInterceptorEngine].
     * Enables Fetch domain with targeted RequestPatterns if drop rules exist,
     * or disables Fetch domain if no drop rules remain.
     */
    fun syncFaultRules() {
        executor.execute {
            val sess = session ?: return@execute
            val activeDropRules = faultInterceptorEngine?.activeDropRules() ?: emptyList()
            if (activeDropRules.isNotEmpty()) {
                val patterns = activeDropRules.flatMap { patternsFor(it) }.distinct()
                enabledPatterns = patterns
                enablePending = true
                val gen = generation.get()
                updateState(
                    state.copy(
                        phase = FetchLifecyclePhase.ENABLING,
                        enabledPatternCount = patterns.size,
                    )
                )
                enableFetch(gen)
            } else {
                enabledPatterns = emptyList()
                if (state.phase == FetchLifecyclePhase.READY || state.phase == FetchLifecyclePhase.ENABLING) {
                    disable()
                }
            }
        }
    }

    internal fun patternsFor(rule: FaultRule): List<RequestPattern> {
        val p = rule.urlPattern.trim()
        val patterns = LinkedHashSet<String>()

        if (FaultInterceptorEngine.isHostOnlyPattern(p)) {
            val (ruleScheme, ruleHost) = FaultInterceptorEngine.extractHostAndScheme(p)
            val schemes = if (ruleScheme != null) listOf(ruleScheme) else listOf("https", "http")
            for (scheme in schemes) {
                patterns.add("$scheme://$ruleHost/*")
                patterns.add("$scheme://$ruleHost")
                patterns.add("$scheme://$ruleHost/")
                // Support origin-wide matching for subdomains under this host
                patterns.add("$scheme://*.$ruleHost/*")
                patterns.add("$scheme://*.$ruleHost")
                patterns.add("$scheme://*.$ruleHost/")
            }
            patterns.add("$ruleHost/*")
            patterns.add(ruleHost)
            patterns.add("$ruleHost/")
        } else {
            val candidates = LinkedHashSet<String>()
            candidates.add(p)
            if (!p.contains("://") && !p.startsWith("*") && !p.startsWith("?")) {
                candidates.add("https://$p")
                candidates.add("http://$p")
            }
            for (cand in candidates) {
                patterns.add(cand)
                if (cand.endsWith("/")) {
                    patterns.add(cand.removeSuffix("/"))
                } else if (!cand.endsWith("*")) {
                    patterns.add("$cand/")
                }
            }
        }
        return patterns.map { RequestPattern(urlPattern = it, requestStage = "Request") }
    }

    /**
     * Binds to an attached session and enables the `Fetch` domain. Any prior
     * target's interception hooks are discarded and its paused-request state is
     * cleared so only this session's requests can be acted upon.
     *
     * In fault-interception mode (when [faultInterceptorEngine] is present),
     * Fetch is only enabled for targeted active drop rules to preserve normal
     * browsing performance.
     *
     * @param patterns Optional `Fetch.enable` [RequestPattern]s; when empty the
     *   domain is enabled for all requests.
     * @param authRequests Whether to handle auth challenges (`Fetch.continueWithAuth`).
     */
    fun bindSession(
        newSession: SessionInfo,
        patterns: List<RequestPattern> = emptyList(),
        authRequests: Boolean = false,
    ) {
        val gen = generation.incrementAndGet()
        session = newSession
        capability = FetchCapabilityStatus.UNKNOWN
        capabilityError = null
        enablePending = true
        authRequestsEnabled = authRequests
        pausedRequestIds.clear()
        clearSubscriptions()
        registerSubscriptions()

        val activeDropRules = faultInterceptorEngine?.activeDropRules() ?: emptyList()
        val effectivePatterns = if (faultInterceptorEngine != null) {
            activeDropRules.flatMap { patternsFor(it) }.distinct()
        } else {
            patterns
        }
        enabledPatterns = effectivePatterns

        val shouldEnable = if (faultInterceptorEngine != null) {
            effectivePatterns.isNotEmpty()
        } else {
            true
        }

        if (shouldEnable) {
            updateState(
                FetchInterceptionState(
                    sessionId = newSession.sessionId,
                    targetId = newSession.targetId,
                    phase = FetchLifecyclePhase.ENABLING,
                    capability = FetchCapabilityStatus.UNKNOWN,
                    enabledPatternCount = effectivePatterns.size,
                    authRequestsEnabled = authRequests,
                )
            )
            enableFetch(gen)
        } else {
            enablePending = false
            capability = FetchCapabilityStatus.SUPPORTED
            updateState(
                FetchInterceptionState(
                    sessionId = newSession.sessionId,
                    targetId = newSession.targetId,
                    phase = FetchLifecyclePhase.INACTIVE,
                    capability = FetchCapabilityStatus.SUPPORTED,
                    enabledPatternCount = 0,
                    authRequestsEnabled = authRequests,
                )
            )
        }
    }

    /** Detaches, clearing all target-scoped interception state and paused IDs. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        enablePending = true
        pausedRequestIds.clear()
        clearSubscriptions()
        updateState(FetchInterceptionState())
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    /**
     * Safely resolves all outstanding paused requests by continuing them untouched,
     * clears authored mock response rules, and resets paused-request bookkeeping.
     * Prevents paused network requests from remaining indefinitely suspended during reset.
     */
    fun resolveOutstandingInterceptions() {
        generation.incrementAndGet()
        val sess = session
        val ids = pausedRequestIds.keys().toList()
        if (sess != null) {
            for (requestId in ids) {
                try {
                    dispatcher.sendCommand(
                        method = "Fetch.continueRequest",
                        params = mapOf("requestId" to requestId),
                        sessionId = sess.sessionId,
                    ) {}
                } catch (_: Exception) {
                    // Ignore exceptions during teardown
                }
            }
        }
        pausedRequestIds.clear()
        ruleManager.clear()
        updateState(state.copy(pendingCount = 0))
    }

    /**
     * Clears all mock response rules, resolves paused requests, and disables Fetch domain if active.
     */
    fun clear() {
        resolveOutstandingInterceptions()
        if (state.phase == FetchLifecyclePhase.READY || state.phase == FetchLifecyclePhase.ENABLING) {
            disable()
        }
    }

    /**
     * Disables interception with `Fetch.disable`. Paused-request state is
     * cleared once the command succeeds so pending IDs from before the disable
     * are no longer actionable.
     */
    fun disable() {
        val sess = session ?: return
        val gen = generation.get()
        dispatcher.sendCommand("Fetch.disable", sessionId = sess.sessionId) { result ->
            executor.execute {
                if (!isCurrent(gen)) return@execute
                pausedRequestIds.clear()
                updateState(
                    state.copy(
                        phase = FetchLifecyclePhase.DISABLED,
                        capability = when (result) {
                            is CommandResult.Success -> FetchCapabilityStatus.SUPPORTED
                            else -> FetchCapabilityStatus.FAILED
                        },
                        capabilityError = (result as? CommandResult.Failure)?.error,
                        error = (result as? CommandResult.Failure)?.error,
                        pendingCount = 0,
                    )
                )
            }
        }
    }

    /** Shuts down the internal executor and unsubscribes from events. */
    fun shutdown() {
        clearSubscriptions()
        executor.shutdownNow()
    }

    // ================================================================
    // Domain enablement
    // ================================================================

    private fun enableFetch(gen: Int) {
        val sess = session ?: return
        val params = LinkedHashMap<String, Any?>()
        if (enabledPatterns.isNotEmpty()) {
            params["patterns"] = enabledPatterns.map { it.toParams() }
        }
        if (authRequestsEnabled) params["handleAuthRequests"] = true
        dispatcher.sendCommand(
            method = "Fetch.enable",
            params = if (params.isEmpty()) null else params,
            sessionId = sess.sessionId,
        ) { result ->
            executor.execute {
                if (!isCurrent(gen)) return@execute
                enablePending = false
                when (result) {
                    is CommandResult.Success -> {
                        capability = FetchCapabilityStatus.SUPPORTED
                        updateState(
                            state.copy(
                                phase = FetchLifecyclePhase.READY,
                                capability = FetchCapabilityStatus.SUPPORTED,
                                capabilityError = null,
                            )
                        )
                    }
                    is CommandResult.Failure -> {
                        capability = classifyCapabilityFailure(result.error)
                        capabilityError = result.error
                        val failedPhase = if (capability == FetchCapabilityStatus.UNSUPPORTED) {
                            FetchLifecyclePhase.UNSUPPORTED
                        } else {
                            FetchLifecyclePhase.FAILED
                        }
                        updateState(
                            state.copy(
                                phase = failedPhase,
                                capability = capability,
                                capabilityError = result.error,
                                error = result.error,
                            )
                        )
                        notifyError(result.error)
                    }
                }
            }
        }
    }

    private fun classifyCapabilityFailure(error: Throwable): FetchCapabilityStatus =
        if (error.message.orEmpty().contains("-32601")) FetchCapabilityStatus.UNSUPPORTED else FetchCapabilityStatus.FAILED

    // ================================================================
    // Event reception & routing
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("Fetch.requestPaused") { event -> onRequestPaused(event) }
    }

    /** Handles a paused request for the active session and routes it. */
    private fun onRequestPaused(event: Event) {
        executor.execute {
            val active = session ?: return@execute
            if (event.sessionId != active.sessionId) return@execute
            if (event.params !is Map<*, *>) return@execute
            val requestId = event.params["requestId"] as? String ?: return@execute
            val request = event.params["request"] as? Map<*, *>
            val method = (request?.get("method") as? String)
                ?: (event.params["method"] as? String)
                ?: "GET"
            val url = (request?.get("url") as? String)
                ?: (event.params["url"] as? String)
                ?: (event.params["documentURL"] as? String)
                ?: ""
            pausedRequestIds[requestId] = active.sessionId
            updateState(
                state.copy(
                    interceptedCount = state.interceptedCount + 1,
                    pendingCount = state.pendingCount + 1,
                )
            )
            routeIntercepted(requestId, url, method)
        }
    }

    /** Applies the rule-manager decision for a paused request. */
    private fun routeIntercepted(requestId: String, url: String, method: String) {
        val dropRule = faultInterceptorEngine?.match(url, method)?.takeIf { it.dropConnection }
        if (dropRule != null) {
            failRequest(requestId, NetworkFailureReason.ConnectionReset)
            return
        }
        val rule = ruleManager.match(url, method)
        when (rule?.action) {
            MockAction.FULFILL -> fulfillRequest(requestId, MockHttpResponse.fromRule(rule))
            MockAction.FAIL -> failRequest(requestId, rule.failureReason)
            else -> continueRequest(requestId)
        }
    }

    // ================================================================
    // Interception actions
    // ================================================================

    /**
     * Serves a mock HTTP [response] for a paused [requestId] via
     * `Fetch.fulfillRequest`.
     */
    fun fulfillRequest(requestId: String, response: MockHttpResponse, callback: ((FetchActionResult) -> Unit)? = null) {
        dispatchPausedAction(requestId, "Fetch.fulfillRequest", response.toFulfillParams(requestId), callback)
    }

    /**
     * Simulates a network [reason] for a paused [requestId] via
     * `Fetch.failRequest`.
     */
    fun failRequest(requestId: String, reason: NetworkFailureReason, callback: ((FetchActionResult) -> Unit)? = null) {
        val params = LinkedHashMap<String, Any?>()
        params["requestId"] = requestId
        params["errorReason"] = reason.cdpValue
        dispatchPausedAction(requestId, "Fetch.failRequest", params, callback)
    }

    /**
     * Continues a paused [requestId] untouched via `Fetch.continueRequest`
     * (the default for un-mocked requests).
     */
    fun continueRequest(requestId: String, callback: ((FetchActionResult) -> Unit)? = null) {
        dispatchPausedAction(requestId, "Fetch.continueRequest", mapOf("requestId" to requestId), callback)
    }

    /**
     * Responds to an auth challenge for a paused [requestId] via
     * `Fetch.continueWithAuth`.
     */
    fun continueWithAuth(requestId: String, auth: ContinueWithAuth, callback: ((FetchActionResult) -> Unit)? = null) {
        dispatchPausedAction(requestId, "Fetch.continueWithAuth", auth.toParams(requestId), callback)
    }

    /**
     * Dispatches a CDP interception command for a paused request, rejecting
     * stale/unknown request IDs without sending. The paused entry is removed
     * once the command is dispatched so no pending decision promise leaks.
     */
    private fun dispatchPausedAction(
        requestId: String,
        method: String,
        params: Map<String, Any?>,
        callback: ((FetchActionResult) -> Unit)?,
    ) {
        val sess = session
        if (sess == null) {
            callback?.invoke(FetchActionResult.NO_SESSION)
            return
        }
        if (!isPausedOn(requestId, sess)) {
            callback?.invoke(FetchActionResult.UNKNOWN_REQUEST)
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(method, params = params, sessionId = sess.sessionId) { result ->
            executor.execute {
                if (!isCurrent(gen)) return@execute
                pausedRequestIds.remove(requestId)
                updateState(state.copy(pendingCount = Math.max(0, state.pendingCount - 1)))
                val outcome = if (result is CommandResult.Success) {
                    FetchActionResult.SENT
                } else {
                    val failure = result as CommandResult.Failure
                    notifyError(failure.error)
                    FetchActionResult.ERROR
                }
                callback?.invoke(outcome)
            }
        }
    }

    /** Whether [requestId] is paused on the active [sess]. */
    private fun isPausedOn(requestId: String, sess: SessionInfo): Boolean =
        pausedRequestIds[requestId] == sess.sessionId

    // ================================================================
    // State / notification helpers
    // ================================================================

    private fun updateState(newState: FetchInterceptionState) {
        state = newState
        listeners.forEach { it.onFetchStateChanged(newState) }
    }

    private fun notifyError(error: Throwable) {
        listeners.forEach { it.onError(NetworkException("Fetch interception error: ${error.message}", cause = error)) }
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }

    private fun isCurrent(gen: Int): Boolean = generation.get() == gen
}