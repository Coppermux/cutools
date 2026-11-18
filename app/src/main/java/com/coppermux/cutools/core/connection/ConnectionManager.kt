package com.coppermux.cutools.core.connection

import com.coppermux.cutools.core.capability.BrowserInfo
import com.coppermux.cutools.core.capability.CapabilityStatus
import com.coppermux.cutools.core.capability.CapabilityRegistry
import com.coppermux.cutools.core.capability.BrowserInfoRetriever
import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.error.TransportException
import com.coppermux.cutools.core.kernel.DiagnosticKernel
import com.coppermux.cutools.core.kernel.KernelCdpAdapter
import com.coppermux.cutools.core.kernel.KernelEvent
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetInfo
import com.coppermux.cutools.core.target.TargetManager
import com.coppermux.cutools.core.target.TargetManagerListener
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CdpTransport
import com.coppermux.cutools.core.transport.WebSocketCdpTransport
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.model.CommandResult
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Central orchestrator for the Chrome debugging connection lifecycle.
 *
 * State machine:
 *   DISCONNECTED → CONNECTING → CONNECTED → DISCOVERING_TARGETS → TARGET_SELECTED
 *   → ATTACHING → ATTACHED
 *
 * On error: → ERROR
 * On disconnect: → DISCONNECTING → DISCONNECTED
 *
 * The ConnectionManager wires together:
 * - CdpTransport (WebSocket)
 * - CommandDispatcher (command/response correlation)
 * - CdpEventRouter (event subscription)
 * - TargetManager (target discovery, selection, attachment)
 * - BrowserInfoRetriever (browser version info)
 * - CapabilityRegistry (capability probing)
 *
 * All operations are asynchronous and must not block the Android UI thread.
 *
 * DiagnosticKernel (Phase 1): the [kernel] is the authoritative owner of
 * diagnostic session identity and epoch. This manager remains the operational
 * owner of the connection and forwards every connection-state change and every
 * TargetManager attach/detach/clear to the kernel via [KernelCdpAdapter]
 * *before* notifying any other listener. The [ConnectionState] machine above is
 * retained for connection-level/UI status only; it does not define session
 * identity.
 */
class ConnectionManager private constructor(
    private val transport: CdpTransport,
    private val config: DispatcherConfig,
    /** The diagnostic session authority this manager feeds. */
    val kernel: DiagnosticKernel,
) : CdpTransport.Listener {

    private val kernelAdapter = KernelCdpAdapter(kernel)

    // Core components — initialized on connect()
    lateinit var eventRouter: CdpEventRouter
        private set
    lateinit var dispatcher: CommandDispatcher
        private set
    private lateinit var _targetManager: TargetManager
    lateinit var browserInfoRetriever: BrowserInfoRetriever
        private set

    /**
     * The target manager for the active connection, or `null` before the
     * connection core has been initialized. Null-safe: never throws
     * [kotlin.UninitializedPropertyAccessException], so callers can safely
     * register listeners during startup / standalone mode.
     */
    val targetManager: TargetManager?
        get() = if (::_targetManager.isInitialized) _targetManager else null

    val capabilityRegistry = CapabilityRegistry()

    // State
    private val state = AtomicReference(ConnectionState.DISCONNECTED)

    // Browser info cache
    private val browserInfoRef = AtomicReference<BrowserInfo?>(null)

    // Last error
    @Volatile
    private var lastError: Throwable? = null

    @Volatile
    private var connectedUrl: String? = null

    // Listeners
    private val stateListeners = CopyOnWriteArrayList<StateListener>()

    // Reconnection state
    @Volatile
    private var autoReconnect = false
    @Volatile
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    // Pending connect callbacks
    private var pendingConnectSuccess: (() -> Unit)? = null
    private var pendingConnectError: ((Throwable) -> Unit)? = null

    /** Current connection state. */
    val connectionState: ConnectionState get() = state.get()

    /** Cached browser information, or null if not yet retrieved. */
    val browserInfo: BrowserInfo? get() = browserInfoRef.get()

    /** Last error that occurred, or null. */
    val lastErrorValue: Throwable? get() = lastError

    // ============== CdpTransport.Listener ==============

    override fun onConnected() {
        state.set(ConnectionState.CONNECTED)
        notifyStateChanged(ConnectionState.CONNECTED, null)
        // Retrieve browser info to complete connection setup
        onConnectionReady()
    }

    override fun onMessage(rawJson: String) {
        // Forward to dispatcher for classification (responses → correlation, events → router)
        if (::dispatcher.isInitialized) {
            dispatcher.onMessage(rawJson)
        }
    }

    override fun onClosed(reason: String) {
        val previous = state.getAndSet(ConnectionState.DISCONNECTED)
        // If we were in ERROR state, keep it — onClosed should not override transport errors
        if (previous == ConnectionState.ERROR) {
            state.set(ConnectionState.ERROR)
            return
        }
        clearBrowserInfo()
        lastError = ConnectionException("Connection closed: $reason")
        notifyStateChanged(ConnectionState.DISCONNECTED, lastError)
        if (::_targetManager.isInitialized) {
            _targetManager.clearAllState()
        }

        pendingConnectError?.invoke(lastError!!)
        pendingConnectSuccess = null
        pendingConnectError = null

        if (previous.isActive() && autoReconnect) {
            attemptReconnect()
        }
    }

    override fun onError(error: TransportException) {
        state.set(ConnectionState.ERROR)
        lastError = error
        notifyStateChanged(ConnectionState.ERROR, error)
        pendingConnectError?.invoke(error)
        pendingConnectError = null
        pendingConnectSuccess = null
        if (autoReconnect) {
            attemptReconnect()
        }
    }

    // ============== Connection lifecycle ==============

    /**
     * Ensures the connection core components (event router, dispatcher, target
     * manager, browser-info retriever) are initialized. Idempotent — no-op if
     * already initialized. Call this before wiring lifecycle observers so
     * startup / standalone mode never hits an uninitialized [targetManager].
     * A subsequent [connect] re-initializes the dispatcher/router for a fresh
     * connection while preserving the target manager (and its listeners).
     */
    fun ensureInitialized() {
        if (!::_targetManager.isInitialized) {
            initCoreComponents()
        }
    }

    /**
     * Establish a connection to the Chrome debugging endpoint.
     *
     * @param url WebSocket debugging URL (e.g., "ws://localhost:9222/devtools/browser").
     * @param onError Called if the connection fails.
     * @param onConnected Called when connection is established and browser info retrieved.
     */
    fun connect(
        url: String,
        onError: ((Throwable) -> Unit)? = null,
        onConnected: (() -> Unit)? = null,
    ) {
        val current = state.get()
        if (current == ConnectionState.CONNECTING) {
            onError?.invoke(ConnectionException("Already connecting. State: $current"))
            return
        }
        if (current.isActive()) {
            disconnect()
        }

        // Ensure connection core components are initialized
        ensureInitialized()

        pendingConnectSuccess = onConnected
        pendingConnectError = onError

        connectedUrl = url
        state.set(ConnectionState.CONNECTING)
        lastError = null
        reconnectAttempts = 0
        notifyStateChanged(ConnectionState.CONNECTING, null)

        transport.connect(url, this)
    }

    private fun initCoreComponents() {
        if (!::eventRouter.isInitialized) {
            eventRouter = CdpEventRouter(kernel = kernel)
        }
        if (!::dispatcher.isInitialized) {
            dispatcher = CommandDispatcher(transport, eventRouter, config)
        }
        if (::_targetManager.isInitialized) {
            // Preserve the existing target manager (and any registered lifecycle
            // listeners) while pointing it at the fresh dispatcher/router for the
            // new connection.
            _targetManager.rebind(dispatcher, eventRouter)
            _targetManager.jsonListFetcher = { callback -> fetchJsonList(callback) }
        } else {
            _targetManager = TargetManager(dispatcher, eventRouter)
            _targetManager.registerTargetDomainEvents()
            _targetManager.jsonListFetcher = { callback -> fetchJsonList(callback) }
            // The kernel adapter must be the first listener so every other
            // listener observes an already-updated kernel snapshot.
            _targetManager.addListener(kernelAdapter)
            wireTargetManagerConnectionSync(_targetManager)
        }
        browserInfoRetriever = BrowserInfoRetriever(dispatcher)
    }

    private fun wireTargetManagerConnectionSync(tm: TargetManager) {
        tm.addListener(object : TargetManagerListener {
            override fun onTargetCreated(target: TargetInfo) {}
            override fun onTargetUpdated(target: TargetInfo) {}
            override fun onTargetDestroyed(targetId: String) {}
            override fun onTargetSelected(target: TargetInfo) {
                if (state.get() == ConnectionState.CONNECTED || state.get() == ConnectionState.DISCOVERING_TARGETS) {
                    state.set(ConnectionState.TARGET_SELECTED)
                    notifyStateChanged(ConnectionState.TARGET_SELECTED, null)
                }
            }
            override fun onTargetsUpdated(targets: List<TargetInfo>) {}
            override fun onAttached(session: SessionInfo) {
                if (state.get() != ConnectionState.ATTACHED) {
                    state.set(ConnectionState.ATTACHED)
                    notifyStateChanged(ConnectionState.ATTACHED, null)
                }
            }
            override fun onDetached(targetId: String) {
                if (tm.activeSession == null && state.get() == ConnectionState.ATTACHED) {
                    state.set(ConnectionState.CONNECTED)
                    notifyStateChanged(ConnectionState.CONNECTED, null)
                }
            }
            override fun onCleared() {
                if (state.get() == ConnectionState.ATTACHED || state.get() == ConnectionState.TARGET_SELECTED) {
                    state.set(ConnectionState.CONNECTED)
                    notifyStateChanged(ConnectionState.CONNECTED, null)
                }
            }
            override fun onError(error: Throwable) {}
        })
    }

    /**
     * Called after WebSocket connection is established.
     * Retrieves browser info and sets up target discovery.
     */
    private fun onConnectionReady() {
        browserInfoRetriever.getBrowserVersion { result ->
            result.onSuccess { info ->
                browserInfoRef.set(info)
                capabilityRegistry.register("browser_info", CapabilityStatus.Supported)
                capabilityRegistry.register("browser_version", CapabilityStatus.Supported)
            }
            result.onFailure { error ->
                capabilityRegistry.register("browser_info", CapabilityStatus.Failed(error.message ?: "Unknown error"))
                lastError = error
                notifyStateChanged(state.get(), error)
            }

            // Enable Target domain for target discovery
            enableTargetDomain(
                onSuccess = {
                    discoverTargets(
                        callback = {
                            pendingConnectSuccess?.invoke()
                            pendingConnectSuccess = null
                            pendingConnectError = null
                        },
                        onError = { error ->
                            handleConnectionError(error)
                        }
                    )
                },
                onError = { error ->
                    handleConnectionError(error)
                }
            )
        }
    }

    private fun enableTargetDomain(
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        dispatcher.sendCommand("Target.setDiscoverTargets", mapOf("discover" to true)) { result ->
            when (result) {
                is CommandResult.Success -> {
                    capabilityRegistry.register("target_discovery", CapabilityStatus.Supported)
                    onSuccess()
                }
                is CommandResult.Failure -> {
                    val error = result.error
                    capabilityRegistry.register("target_discovery", CapabilityStatus.Failed(
                        error.message ?: "Failed to enable target discovery"
                    ))
                    onError(error)
                }
            }
        }
    }

    private fun handleConnectionError(error: Throwable) {
        state.set(ConnectionState.ERROR)
        lastError = error
        notifyStateChanged(ConnectionState.ERROR, error)
        pendingConnectError?.invoke(error)
        pendingConnectError = null
        pendingConnectSuccess = null
    }

    /**
     * Discover available targets.
     */
    fun discoverTargets(
        callback: (List<TargetInfo>) -> Unit,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        val current = state.get()
        if (current != ConnectionState.CONNECTED &&
            current != ConnectionState.DISCOVERING_TARGETS &&
            current != ConnectionState.TARGET_SELECTED &&
            current != ConnectionState.ATTACHING &&
            current != ConnectionState.ATTACHED
        ) {
            onError?.invoke(ConnectionException("Not connected. Current state: $current"))
            return
        }

        if (current == ConnectionState.CONNECTED) {
            state.set(ConnectionState.DISCOVERING_TARGETS)
            notifyStateChanged(ConnectionState.DISCOVERING_TARGETS, null)
        }

        _targetManager.discoverTargets { targets ->
            if (state.get() == ConnectionState.DISCOVERING_TARGETS) {
                state.set(ConnectionState.CONNECTED)
                notifyStateChanged(ConnectionState.CONNECTED, null)
            }
            callback(targets)
        }
    }

    /**
     * Select a target by ID.
     */
    fun selectTarget(targetId: String) {
        val current = state.get()
        if (current != ConnectionState.CONNECTED &&
            current != ConnectionState.DISCOVERING_TARGETS &&
            current != ConnectionState.TARGET_SELECTED &&
            current != ConnectionState.ATTACHED
        ) {
            return
        }
        if (current == ConnectionState.CONNECTED || current == ConnectionState.DISCOVERING_TARGETS) {
            state.set(ConnectionState.TARGET_SELECTED)
            notifyStateChanged(ConnectionState.TARGET_SELECTED, null)
        }
        _targetManager.selectTarget(targetId)
    }

    /**
     * Attach to the selected target.
     */
    fun attachToTarget(
        onAttached: ((SessionInfo) -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        val current = state.get()
        if (current != ConnectionState.TARGET_SELECTED) {
            onError?.invoke(ConnectionException("No target selected. Current state: $current"))
            return
        }

        state.set(ConnectionState.ATTACHING)
        notifyStateChanged(ConnectionState.ATTACHING, null)

        _targetManager.attachToSelectedTarget { result ->
            result.onSuccess { session ->
                state.set(ConnectionState.ATTACHED)
                notifyStateChanged(ConnectionState.ATTACHED, null)
                onAttached?.invoke(session)
            }
            result.onFailure { error ->
                state.set(ConnectionState.ERROR)
                lastError = error
                notifyStateChanged(ConnectionState.ERROR, error)
                onError?.invoke(error)
            }
        }
    }

    /**
     * Probes the Chromium DevTools `/json/list` HTTP endpoint for target descriptions,
     * resolving whether each target is currently attached to the window hierarchy.
     */
    fun fetchJsonList(callback: (List<Map<String, Any?>>) -> Unit) {
        val rawUrl = connectedUrl ?: run {
            callback(emptyList())
            return
        }
        val httpEquivalent = rawUrl
            .replaceFirst("^ws://".toRegex(RegexOption.IGNORE_CASE), "http://")
            .replaceFirst("^wss://".toRegex(RegexOption.IGNORE_CASE), "https://")
        val parsed = httpEquivalent.toHttpUrlOrNull()
        val httpBase = if (parsed != null) {
            "${parsed.scheme}://${parsed.host}:${parsed.port}"
        } else {
            httpEquivalent.substringBefore("/devtools").substringBefore("/json")
        }
        val queryPart = parsed?.query?.let { "?$it" } ?: ""
        val jsonListUrl = "$httpBase/json/list$queryPart"
        val request = Request.Builder().url(jsonListUrl).build()
        val client = OkHttpClient()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(emptyList())
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        callback(emptyList())
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    val parsed = parseJsonList(body)
                    callback(parsed)
                }
            }
        })
    }

    private fun parseJsonList(json: String): List<Map<String, Any?>> {
        val list = mutableListOf<Map<String, Any?>>()
        val clean = json.trim()
        if (!clean.startsWith("[") || !clean.endsWith("]")) return emptyList()
        try {
            val jsonArray = JSONArray(clean)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val map = mutableMapOf<String, Any?>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = obj.opt(k)
                }
                list.add(map)
            }
        } catch (_: Throwable) {
        }
        return list
    }

    /**
     * Detach from the currently attached target.
     */
    fun detachFromTarget(
        onDetached: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        if (state.get() != ConnectionState.ATTACHED) {
            onDetached?.invoke()
            return
        }

        _targetManager.detachFromSelectedTarget { result ->
            result.onSuccess {
                // Clear session state
                state.set(ConnectionState.CONNECTED)
                notifyStateChanged(ConnectionState.CONNECTED, null)
                onDetached?.invoke()
            }
            result.onFailure { error ->
                state.set(ConnectionState.ERROR)
                lastError = error
                notifyStateChanged(ConnectionState.ERROR, error)
                onError?.invoke(error)
            }
        }
    }

    /**
     * Disconnect cleanly. Cleans up pending commands, sessions, target state,
     * and closes the transport.
     */
    fun disconnect() {
        val current = state.get()
        if (!current.isActive() && current != ConnectionState.ATTACHED) {
            return
        }

        state.set(ConnectionState.DISCONNECTING)
        notifyStateChanged(ConnectionState.DISCONNECTING, null)

        // Clean up sessions and targets
        if (::_targetManager.isInitialized) {
            _targetManager.clearAllState()
        }

        clearBrowserInfo()

        // Cancel pending commands/timeouts without terminating the dispatcher's executor
        if (::dispatcher.isInitialized) {
            dispatcher.failAllPending(ConnectionException("Disconnected"))
        }

        // Close transport
        transport.disconnect()

        state.set(ConnectionState.DISCONNECTED)
        lastError = null
        notifyStateChanged(ConnectionState.DISCONNECTED, null)
    }

    /**
     * Completely shuts down the ConnectionManager and its dispatcher's executor.
     * Should be called during application teardown or test cleanup.
     */
    fun shutdown() {
        disconnect()
        if (::dispatcher.isInitialized) {
            dispatcher.shutdown()
        }
    }

    private fun clearBrowserInfo() {
        browserInfoRef.set(null)
    }

    // ============== State listeners ==============

    /** Listener for connection state changes. */
    interface StateListener {
        fun onStateChanged(state: ConnectionState, error: Throwable?)
    }

    fun addStateListener(listener: StateListener) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: StateListener) {
        stateListeners.remove(listener)
    }

    private fun notifyStateChanged(newState: ConnectionState, error: Throwable?) {
        // Kernel first, so state listeners observe an already-updated kernel.
        forwardToKernel { kernelAdapter.onConnectionStateChanged(newState, error) }
        stateListeners.forEach { it.onStateChanged(newState, error) }
    }

    private inline fun forwardToKernel(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // The kernel reducer is total and does not throw; this guard only
            // ensures a kernel defect can never break the connection lifecycle.
            System.err.println("[CuTools/ConnectionManager] DiagnosticKernel forwarding failed: $t")
        }
    }

    // ============== Reconnection ==============

    /**
     * Enable automatic reconnection on connection failure.
     */
    fun setAutoReconnect(enabled: Boolean) {
        autoReconnect = enabled
    }

    private fun attemptReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            lastError = ConnectionException("Max reconnection attempts ($maxReconnectAttempts) exceeded")
            state.set(ConnectionState.ERROR)
            notifyStateChanged(ConnectionState.ERROR, lastError)
            return
        }
        reconnectAttempts++
        state.set(ConnectionState.CONNECTING)
        notifyStateChanged(ConnectionState.CONNECTING, ConnectionException("Reconnecting (attempt $reconnectAttempts)..."))
        // Caller must re-invoke connect() to actually reconnect
    }

    /**
     * Reset all state for a fresh connection. Called before reconnection.
     */
    fun reset() {
        // Explicitly set state to DISCONNECTED first so TargetManager onCleared sync
        // does not resurrect state to CONNECTED.
        state.set(ConnectionState.DISCONNECTED)
        forwardToKernel { kernel.dispatch(KernelEvent.ConnectionLost("ConnectionManager.reset")) }
        if (::_targetManager.isInitialized) {
            _targetManager.clearAllState()
        }
        clearBrowserInfo()
        lastError = null
        reconnectAttempts = 0
        capabilityRegistry.clear()
    }

    // ============== Factory ==============

    companion object {
        /** Default local Chrome remote debugging WebSocket URL. */
        const val DEFAULT_DEBUG_URL = "ws://127.0.0.1:9222/devtools/browser"

        /** Canonical test Chrome remote debugging WebSocket URL (Phase 14). */
        const val CANONICAL_TEST_DEBUG_URL = "ws://127.0.0.1:9224/devtools/browser"

        /**
         * Create a ConnectionManager with the given transport and config.
         * [kernel] defaults to a private kernel so existing call sites (tests,
         * integration tests) keep working; the application injects its shared
         * kernel.
         */
        fun create(
            transport: CdpTransport,
            config: DispatcherConfig = DispatcherConfig(),
            kernel: DiagnosticKernel = DiagnosticKernel(),
        ): ConnectionManager = ConnectionManager(transport, config, kernel)
    }
}
