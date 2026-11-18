package com.coppermux.cutools.core.network

import com.coppermux.cutools.core.browser.DashboardEventClassifier
import com.coppermux.cutools.core.browser.DashboardEventStream
import com.coppermux.cutools.core.diagnostic.DiagnosticTracer
import com.coppermux.cutools.core.error.NetworkException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "CuTools/NetworkEngine"

private fun logTrace(tag: String, message: String) {
    try {
        android.util.Log.d(tag, message)
    } catch (_: Throwable) {}
    println("[$tag] $message")
}

/** Listener for Network state / error changes. */
interface NetworkEngineListener {
    fun onNetworkStateChanged(state: NetworkState)
    fun onError(error: Throwable)
}

/**
 * Orchestrates Network monitoring for the currently attached page target.
 *
 * Phase 5A — Network domain foundation & lifecycle tracking:
 * - Binds to an attached CDP session, enabling the `Network` domain
 *   (`Network.enable`).
 * - Subscribes to the four `Network` lifecycle events and applies them to a
 *   thread-safe in-memory [NetworkRequestStore], keyed by Chrome's requestId,
 *   advancing each request across pending → response → finished/failed.
 * - Publishes explicit [NetworkState] with structured [NetworkRequestModel]s.
 * - Handles target detach/reconnect gracefully by clearing the request store
 *   and re-binding event subscriptions — stale events for a previous session
 *   are filtered by session and never throw stale-state exceptions.
 *
 * Phase 5B — Response body retrieval & timing metrics:
 * - [getResponseBody] issues async `Network.getResponseBody` for a requestId
 *   and decodes the body (text or base64/binary) via [ResponseBodyDecoder].
 * - Missing/evicted bodies and protocol errors resolve to explicit
 *   [ResponseBodyStatus] (UNAVAILABLE / UNSUPPORTED / FAILED) rather than
 *   throwing uncaught exceptions.
 * - Request models carry a parsed [ResourceTiming] waterfall from the
 *   `Network.responseReceived` `timing` payload.
 *
 * Every request is tied to its session/target, and state is cleared on target
 * switch / detach / connection loss so a previous target's data never becomes
 * the new target's.
 *
 * All Chrome communication reuses DevTools Core; no second transport, WebSocket,
 * or dispatcher is created.
 *
 * Threading: Network state is mutated on a single-thread executor; listeners
 * are invoked on that executor thread, so UI callers must marshal onto the
 * Android UI thread.
 */
class NetworkEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
    private val storeConfig: NetworkStoreConfig = NetworkStoreConfig(),
    @Volatile
    private var dashboardEventStream: DashboardEventStream? = null,
    private val executor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cutools-network").apply { isDaemon = true }
    },
) {

    /** Read-only Network state. */
    @Volatile
    var state: NetworkState = NetworkState.empty()
        private set

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    private val listeners = CopyOnWriteArrayList<NetworkEngineListener>()
    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()
    private val store = NetworkRequestStore(storeConfig)

    /** Approximate retained request bytes (bounded by [storeConfig]). */
    val retainedBytes: Long get() = store.retainedBytes

    /** Number of entries automatically evicted by the bounded request store. */
    val evictions: Long get() = store.evictions

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    @Volatile
    var isNetworkDropped: Boolean = false
        private set

    private val emulationGeneration = AtomicInteger(0)

    /**
     * Applies CDP Network.emulateNetworkConditions for offline simulation.
     * Safe to call at any time; if no session exists or CDP fails, it non-fatally logs.
     * Stale callbacks are rejected via [emulationGeneration].
     */
    fun setNetworkDrop(offline: Boolean) {
        isNetworkDropped = offline
        val sess = session
        DiagnosticTracer.trace(
            category = "CDP_NETWORK",
            event = "CDP_SET_NETWORK_DROP_CALLED",
            "offline" to offline,
            "hasSession" to (sess != null),
            "sessionId" to sess?.sessionId,
            "targetId" to sess?.targetId,
        )
        if (sess == null) return
        val gen = emulationGeneration.incrementAndGet()
        val params = mapOf(
            "offline" to offline,
            "latency" to 0,
            "downloadThroughput" to -1,
            "uploadThroughput" to -1,
        )
        DiagnosticTracer.trace(
            category = "CDP_NETWORK",
            event = "CDP_EMULATE_NETWORK_DISPATCH",
            "gen" to gen,
            "offline" to offline,
            "sessionId" to sess.sessionId,
            "targetId" to sess.targetId,
        )
        try {
            dispatcher.sendCommand(
                method = "Network.emulateNetworkConditions",
                params = params,
                sessionId = sess.sessionId,
            ) { result ->
                if (emulationGeneration.get() != gen) {
                    DiagnosticTracer.trace(
                        category = "CDP_NETWORK",
                        event = "CDP_EMULATE_NETWORK_STALE_IGNORED",
                        "gen" to gen,
                        "currentGen" to emulationGeneration.get(),
                    )
                    return@sendCommand
                }
                when (result) {
                    is CommandResult.Success -> {
                        DiagnosticTracer.trace(
                            category = "CDP_NETWORK",
                            event = "CDP_EMULATE_NETWORK_SUCCESS",
                            "gen" to gen,
                            "offline" to offline,
                            "sessionId" to sess.sessionId,
                            "targetId" to sess.targetId,
                        )
                        logTrace(TAG, "CDP Network.emulateNetworkConditions(offline=$offline) applied")
                    }
                    is CommandResult.Failure -> {
                        DiagnosticTracer.trace(
                            category = "CDP_NETWORK",
                            event = "CDP_EMULATE_NETWORK_FAILURE",
                            "gen" to gen,
                            "offline" to offline,
                            "error" to result.error.message,
                            "sessionId" to sess.sessionId,
                            "targetId" to sess.targetId,
                        )
                        logTrace(TAG, "CDP Network.emulateNetworkConditions failed: ${result.error}")
                    }
                }
            }
        } catch (t: Throwable) {
            DiagnosticTracer.trace(
                category = "CDP_NETWORK",
                event = "CDP_EMULATE_NETWORK_DISPATCH_EXCEPTION",
                "error" to (t.message ?: t.toString()),
            )
            logTrace(TAG, "CDP Network.emulateNetworkConditions dispatch error: ${t.message}")
        }
    }

    /**
     * Diagnostic testing control for Service Worker and cache bypass (Requirement 3).
     */
    fun applyDiagnosticCacheControls(bypassServiceWorker: Boolean = true, cacheDisabled: Boolean = true) {
        val sess = session
        DiagnosticTracer.trace(
            category = "CDP_DIAGNOSTIC",
            event = "APPLY_CACHE_CONTROLS_CALLED",
            "bypassServiceWorker" to bypassServiceWorker,
            "cacheDisabled" to cacheDisabled,
            "hasSession" to (sess != null),
            "sessionId" to sess?.sessionId,
            "targetId" to sess?.targetId,
        )
        if (sess == null) return
        try {
            dispatcher.sendCommand(
                method = "Network.setBypassServiceWorker",
                params = mapOf("bypass" to bypassServiceWorker),
                sessionId = sess.sessionId,
            ) { result ->
                DiagnosticTracer.trace(
                    category = "CDP_DIAGNOSTIC",
                    event = "SET_BYPASS_SERVICE_WORKER_RESULT",
                    "success" to (result is CommandResult.Success),
                    "error" to (result as? CommandResult.Failure)?.error?.message,
                    "sessionId" to sess.sessionId,
                )
            }
            dispatcher.sendCommand(
                method = "Network.setCacheDisabled",
                params = mapOf("cacheDisabled" to cacheDisabled),
                sessionId = sess.sessionId,
            ) { result ->
                DiagnosticTracer.trace(
                    category = "CDP_DIAGNOSTIC",
                    event = "SET_CACHE_DISABLED_RESULT",
                    "success" to (result is CommandResult.Success),
                    "error" to (result as? CommandResult.Failure)?.error?.message,
                    "sessionId" to sess.sessionId,
                )
            }
        } catch (t: Throwable) {
            DiagnosticTracer.trace(
                category = "CDP_DIAGNOSTIC",
                event = "APPLY_CACHE_CONTROLS_EXCEPTION",
                "error" to (t.message ?: t.toString()),
            )
        }
    }

    @Volatile
    private var capability = NetworkCapabilityStatus.UNKNOWN
    @Volatile
    private var capabilityError: Throwable? = null

    private var enablePending = true

    private val listener = CdpNetworkEventListener(store)
    private val requestGenerations = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val ingressLock = Any()
    private val mutationLock = Any()
    internal var preDashboardPublicationHook: (() -> Unit)? = null
    internal var clearGenerationBoundaryHook: (() -> Unit)? = null
    internal var clearMutationBoundaryHook: (() -> Unit)? = null
    internal var preIngressLockHook: (() -> Unit)? = null

    fun addListener(listener: NetworkEngineListener) { listeners.add(listener) }
    fun removeListener(listener: NetworkEngineListener) { listeners.remove(listener) }

    fun attachDashboardEventStream(stream: DashboardEventStream) {
        dashboardEventStream = stream
    }

    fun detachDashboardEventStream() {
        dashboardEventStream = null
    }

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

    // ================================================================
    // Lifecycle
    // ================================================================

    /**
     * Binds to an attached session and initializes Network event reception.
     * Any prior target's requests are discarded; only events for this session
     * are accepted from this point on.
     */
    fun bindSession(newSession: SessionInfo) {
        val gen: Int
        var newState: NetworkState
        var listenersToNotify: List<NetworkEngineListener>
        synchronized(mutationLock) {
            session = newSession
            capability = NetworkCapabilityStatus.UNKNOWN
            capabilityError = null
            enablePending = true
            synchronized(ingressLock) {
                gen = generation.incrementAndGet()
                requestGenerations.clear()
            }
            store.clear()
            clearSubscriptions()
            registerSubscriptions()
            newState = NetworkState(
                requests = emptyList(),
                sessionId = newSession.sessionId,
                targetId = newSession.targetId,
                phase = NetworkLifecyclePhase.ENABLING,
                capability = NetworkCapabilityStatus.UNKNOWN,
            )
            state = newState
            listenersToNotify = listeners.toList()
        }
        listenersToNotify.forEach { it.onNetworkStateChanged(newState) }
        enableNetwork(gen)
        if (isNetworkDropped) {
            setNetworkDrop(true)
        }
    }

    /** Detaches, clearing all target-scoped Network state. */
    fun detach() {
        emulationGeneration.incrementAndGet()
        var newState: NetworkState
        var listenersToNotify: List<NetworkEngineListener>
        synchronized(mutationLock) {
            session = null
            enablePending = true
            synchronized(ingressLock) {
                generation.incrementAndGet()
                requestGenerations.clear()
            }
            store.clear()
            clearSubscriptions()
            newState = NetworkState()
            state = newState
            listenersToNotify = listeners.toList()
        }
        listenersToNotify.forEach { it.onNetworkStateChanged(newState) }
    }

    /**
     * Clears tracked network requests, records pre-reset request IDs, and resets the attached
     * dashboard event stream under [mutationLock].
     * Prevents late in-flight CDP completion events for pre-reset requests from resurrecting
     * cleared requests or updating the dashboard, while ensuring valid post-reset events cannot
     * be published before the dashboard stream is reset.
     *
     * Synchronization invariant:
     * Generation advancement and request-generation bookkeeping are atomic under [ingressLock].
     * Holding [mutationLock] serializes state mutation, dashboard reset, and event processing on the executor.
     * Any ingress event entering [onNetworkEvent] under [ingressLock] either captures the generation
     * prior to reset (and is rejected on executor as pre-reset traffic), or captures the generation
     * after reset (and cannot have its active generation stamp overwritten by stale-ID bookkeeping).
     * Because [dashboardEventStream] is reset while holding [mutationLock], post-reset events cannot
     * publish to the stream until after the stream has been reset.
     */
    fun clear() {
        var newState: NetworkState
        var listenersToNotify: List<NetworkEngineListener>
        synchronized(mutationLock) {
            val gen: Int
            synchronized(ingressLock) {
                gen = generation.incrementAndGet()
                clearGenerationBoundaryHook?.invoke()
                for (req in store.snapshot()) {
                    requestGenerations[req.requestId] = gen - 1
                }
            }
            clearMutationBoundaryHook?.invoke()
            store.clear()
            dashboardEventStream?.reset()
            newState = state.copy(requests = emptyList())
            state = newState
            listenersToNotify = listeners.toList()
        }
        listenersToNotify.forEach { it.onNetworkStateChanged(newState) }
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    /** Shuts down the internal executor and unsubscribes from events. */
    fun shutdown() {
        clearSubscriptions()
        executor.shutdownNow()
    }

    // ================================================================
    // Domain enablement
    // ================================================================

    private fun enableNetwork(gen: Int) {
        val sess = session ?: return
        dispatcher.sendCommand("Network.enable", sessionId = sess.sessionId) { result ->
            executor.execute {
                if (!isCurrent(gen)) return@execute
                enablePending = false
                when (result) {
                    is CommandResult.Success -> {
                        capability = NetworkCapabilityStatus.SUPPORTED
                        updateState(
                            state.copy(
                                phase = NetworkLifecyclePhase.READY,
                                capability = NetworkCapabilityStatus.SUPPORTED,
                                capabilityError = null,
                            )
                        )
                        DiagnosticTracer.trace(
                            category = "CDP_NETWORK",
                            event = "NETWORK_ENABLE_SUCCESS",
                            "sessionId" to sess.sessionId,
                            "targetId" to sess.targetId,
                        )
                        applyDiagnosticCacheControls(bypassServiceWorker = true, cacheDisabled = true)
                    }
                    is CommandResult.Failure -> {
                        capability = classifyCapabilityFailure(result.error)
                        capabilityError = result.error
                        val failedPhase = if (capability == NetworkCapabilityStatus.UNSUPPORTED) {
                            NetworkLifecyclePhase.UNSUPPORTED
                        } else {
                            NetworkLifecyclePhase.FAILED
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

    private fun classifyCapabilityFailure(error: Throwable): NetworkCapabilityStatus =
        if (error.message.orEmpty().contains("-32601")) NetworkCapabilityStatus.UNSUPPORTED else NetworkCapabilityStatus.FAILED

    // ================================================================
    // Response body retrieval
    // ================================================================

    /**
     * Asynchronously fetches the response body for a tracked [requestId] via
     * `Network.getResponseBody`.
     *
     * The decoded body (or an explicit [ResponseBodyStatus]) is delivered to
     * [callback]. Missing/evicted bodies resolve to
     * [ResponseBodyStatus.UNAVAILABLE], an unavailable method to
     * [ResponseBodyStatus.UNSUPPORTED], and other protocol failures to
     * [ResponseBodyStatus.FAILED] — nothing is thrown to the caller.
     */
    fun getResponseBody(requestId: String, callback: (ResponseBodyResult) -> Unit) {
        val sess = session
        if (sess == null || capability != NetworkCapabilityStatus.SUPPORTED) {
            callback(ResponseBodyResult(
                status = ResponseBodyStatus.UNAVAILABLE,
                error = "Network domain is not bound to an active session",
            ))
            return
        }
        dispatcher.sendCommand(
            method = "Network.getResponseBody",
            params = mapOf("requestId" to requestId),
            sessionId = sess.sessionId,
        ) { result ->
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result as? Map<*, *>
                    val body = payload?.get("body") as? String
                    val base64Encoded = (payload?.get("base64Encoded") as? Boolean) ?: false
                    callback(ResponseBodyDecoder.decode(body, base64Encoded))
                }
                is CommandResult.Failure -> {
                    callback(classifyBodyFailure(result.error))
                }
            }
        }
    }

    /** Maps a `Network.getResponseBody` failure to an explicit [ResponseBodyStatus]. */
    private fun classifyBodyFailure(error: Throwable): ResponseBodyResult {
        val message = error.message.orEmpty()
        val status = if (message.contains("-32601")) {
            ResponseBodyStatus.UNSUPPORTED
        } else if (
            message.contains("not found") ||
                message.contains("evicted") ||
                message.contains("unavailable") ||
                message.contains("no longer available") ||
                message.contains("cannot find")
        ) {
            ResponseBodyStatus.UNAVAILABLE
        } else {
            ResponseBodyStatus.FAILED
        }
        return ResponseBodyResult(status = status, error = message)
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("Network.requestWillBeSent") { event -> onNetworkEvent(event) }
        subscriptions += eventRouter.subscribe("Network.responseReceived") { event -> onNetworkEvent(event) }
        subscriptions += eventRouter.subscribe("Network.loadingFinished") { event -> onNetworkEvent(event) }
        subscriptions += eventRouter.subscribe("Network.loadingFailed") { event -> onNetworkEvent(event) }
    }

    /** Applies a Network event on the executor if it belongs to the active session. */
    private fun onNetworkEvent(event: Event) {
        val requestId = (event.params as? Map<*, *>)?.get("requestId") as? String
        val isNewRequest = (event.method == "Network.requestWillBeSent")
        val ingressGeneration: Int

        when (event.method) {
            "Network.requestWillBeSent" -> {
                val params = event.params as? Map<*, *>
                val req = params?.get("request") as? Map<*, *>
                DiagnosticTracer.trace(
                    category = "CDP_EVENT",
                    event = "Network.requestWillBeSent",
                    "requestId" to requestId,
                    "url" to req?.get("url"),
                    "method" to req?.get("method"),
                    "type" to params?.get("type"),
                )
            }
            "Network.responseReceived" -> {
                val params = event.params as? Map<*, *>
                val resp = params?.get("response") as? Map<*, *>
                DiagnosticTracer.trace(
                    category = "CDP_EVENT",
                    event = "Network.responseReceived",
                    "requestId" to requestId,
                    "url" to resp?.get("url"),
                    "status" to resp?.get("status"),
                    "fromDiskCache" to resp?.get("fromDiskCache"),
                    "fromServiceWorker" to resp?.get("fromServiceWorker"),
                )
            }
            "Network.loadingFailed" -> {
                val params = event.params as? Map<*, *>
                DiagnosticTracer.trace(
                    category = "CDP_EVENT",
                    event = "Network.loadingFailed",
                    "requestId" to requestId,
                    "errorText" to params?.get("errorText"),
                    "canceled" to params?.get("canceled"),
                    "blockedReason" to params?.get("blockedReason"),
                )
            }
        }

        preIngressLockHook?.invoke()
        synchronized(ingressLock) {
            ingressGeneration = generation.get()
            if (requestId != null && isNewRequest) {
                // When requestWillBeSent enters the pipeline on the WebSocket thread,
                // synchronously stamp its request ID with the active generation.
                // Request ID reuse safely re-stamps the ID under the current generation.
                requestGenerations[requestId] = ingressGeneration
            }
        }

        executor.execute {
            val active = session ?: return@execute
            if (event.sessionId != active.sessionId) return@execute

            var listenersToNotify: List<NetworkEngineListener>? = null
            var updatedState: NetworkState? = null

            synchronized(mutationLock) {
                val activeGen = generation.get()

                // Immutable ingress generation validation: pre-reset events can never mutate post-reset state
                if (ingressGeneration < activeGen) {
                    return@execute
                }

                if (requestId != null) {
                    val reqGen = requestGenerations[requestId]
                    // If request was initiated in a previous generation, it belongs to pre-reset in-flight traffic.
                    // Drop all later events belonging to it and do not recreate a card or store entry.
                    if (reqGen != null && reqGen < activeGen) {
                        if (event.method == "Network.loadingFinished" || event.method == "Network.loadingFailed") {
                            requestGenerations.remove(requestId)
                        }
                        return@execute
                    }
                }

                try {
                    val updatedModel = listener.apply(event, active)
                    if (updatedModel != null) {
                        preDashboardPublicationHook?.invoke()
                        if (generation.get() != activeGen) {
                            return@execute
                        }
                        dashboardEventStream?.let { stream ->
                            val dashboardEvent = DashboardEventClassifier.buildFromNetworkRequest(updatedModel)
                            stream.upsertByRequestId(
                                requestId = updatedModel.requestId,
                                create = { dashboardEvent },
                                transform = { existing ->
                                    dashboardEvent.copy(id = existing.id)
                                },
                            )
                        }
                        updatedState = state.copy(requests = store.snapshot())
                        state = updatedState!!
                        listenersToNotify = listeners.toList()
                    }
                    if (event.method == "Network.loadingFinished" || event.method == "Network.loadingFailed") {
                        requestGenerations.remove(requestId)
                    }
                } catch (e: Exception) {
                    notifyError(NetworkException("Failed to apply network event ${event.method}: ${e.message}", cause = e))
                }
            }

            listenersToNotify?.let { list ->
                updatedState?.let { s ->
                    list.forEach { it.onNetworkStateChanged(s) }
                }
            }
        }
    }

    /** Publishes a fresh snapshot of the request store as the new [NetworkState] if generation matches. */
    private fun refreshState(taskGen: Int) {
        if (taskGen == generation.get()) {
            updateState(state.copy(requests = store.snapshot()))
        }
    }

    // ================================================================
    // State / notification helpers
    // ================================================================

    private fun updateState(newState: NetworkState) {
        state = newState
        listeners.forEach { it.onNetworkStateChanged(newState) }
    }

    private fun notifyError(error: Throwable) {
        listeners.forEach { it.onError(error) }
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }

    private fun isCurrent(gen: Int): Boolean = generation.get() == gen
}