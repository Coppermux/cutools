package com.coppermux.cutools.core.transport

import com.coppermux.cutools.core.error.TransportException
import com.coppermux.cutools.core.lifecycle.LifecycleWatcher
import com.coppermux.cutools.core.telemetry.TelemetryEngine
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Resilience wrapper around a raw [CdpTransport] — the Phase 15 transport
 * lifecycle & leak-safety layer.
 *
 * Adds to a plain socket:
 * - **Exponential backoff auto-reconnect** (with jitter and a retry cap) driven
 *   by [ReconnectPolicy]. A dropped socket schedules a re-connect attempt after
 *   the backoff delay; the attempt counter resets after a successful recovery,
 *   so consecutive outage episodes never share a stale backoff ladder.
 * - **Active channel heartbeat monitoring**: while connected, a periodic check
 *   compares the last observed message age against
 *   [ReconnectPolicy.heartbeatTimeoutMs]; a stale channel (high-latency ADB
 *   forward, silent WebSocket) is force-disconnected to trigger a clean
 *   reconnect cycle.
 * - **Clean domain resubscription**: domains registered via [registerDomain]
 *   (e.g., `Target`, `Memory`, `Performance`) are handed to [onResubscribe]
 *   after a successful re-establishment, so the caller can re-issue
 *   `Domain.enable` without fabricating any session state.
 * - **Explicit lifecycle teardown**: [disconnect] cancels every scheduled
 *   reconnect/heartbeat task, shuts down the scheduler, clears the outer
 *   listener, and closes the internal [LifecycleWatcher] — zero leaked jobs.
 *
 * Connection truth always comes from the underlying transport ([isConnected]);
 * this wrapper never invents state. All transitions are guarded, and every
 * telemetry hook (when a [TelemetryEngine] is supplied) records only genuine
 * reconnect outcomes.
 */
class CdpSession(
    private val transport: CdpTransport,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val backoff: BackoffCalculator = BackoffCalculator(policy),
    scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cdp-session").apply { isDaemon = true }
    },
    private val timeSource: () -> Long = { System.currentTimeMillis() },
    private val telemetry: TelemetryEngine? = null,
) : CdpTransport, CdpTransport.Listener {

    /** Invoked after a successful re-establishment with the domains to re-enable. */
    @Volatile
    var onResubscribe: ((Set<String>) -> Unit)? = null

    /** Invoked when the retry cap is reached and reconnection is given up. */
    @Volatile
    var onReconnectExhausted: (() -> Unit)? = null

    private val lifecycle = LifecycleWatcher()
    private val scheduler: ScheduledExecutorService = scheduler
    private val activeDomains = CopyOnWriteArraySet<String>()
    private val reconnectFuture = AtomicReference<ScheduledFuture<*>?>(null)
    private val heartbeatFuture = AtomicReference<ScheduledFuture<*>?>(null)

    @Volatile
    private var outerListener: CdpTransport.Listener? = null

    @Volatile
    private var url: String? = null

    @Volatile
    private var lastMessageAtMs: Long = 0L

    @Volatile
    private var reconnectAttemptCount = 0

    @Volatile
    private var lastBackoffDelayMs: Long = 0L

    @Volatile
    private var reconnectExhaustedFlag = false

    @Volatile
    private var lastDisconnectReason: String? = null

    private val lock = Any()
    private var reconnectPending = false
    private var exhaustedNotified = false
    private var closed = false

    init {
        lifecycle.onClose {
            reconnectFuture.getAndSet(null)?.cancel(false)
            heartbeatFuture.getAndSet(null)?.cancel(false)
            runCatching { scheduler.shutdownNow() }
        }
    }

    // ================================================================
    // CdpTransport API
    // ================================================================

    override val isConnected: Boolean get() = transport.isConnected

    override fun connect(url: String, listener: CdpTransport.Listener) {
        synchronized(lock) {
            this.url = url
            this.outerListener = listener
            this.reconnectPending = false
            this.exhaustedNotified = false
            this.closed = false
        }
        this.reconnectAttemptCount = 0
        this.reconnectExhaustedFlag = false
        this.lastDisconnectReason = null
        transport.connect(url, this)
    }

    override fun send(rawMessage: String) {
        transport.send(rawMessage)
    }

    /**
     * Terminal teardown: cancels every scheduled reconnect/heartbeat task,
     * shuts down the scheduler, clears the outer listener and registered
     * domains, and closes the internal lifecycle watcher. Subsequent calls are
     * no-ops.
     */
    override fun disconnect() {
        synchronized(lock) {
            closed = true
            outerListener = null
            reconnectPending = false
        }
        lastMessageAtMs = 0L
        activeDomains.clear()
        transport.disconnect()
        lifecycle.close()
    }

    /** Alias for [disconnect] (scope-closure semantics). */
    fun close() = disconnect()

    // ================================================================
    // CdpTransport.Listener (inbound forwarding + resilience hooks)
    // ================================================================

    override fun onConnected() {
        val wasReconnect: Boolean
        synchronized(lock) {
            wasReconnect = reconnectAttemptCount > 0
            reconnectPending = false
            exhaustedNotified = false
            reconnectAttemptCount = 0
            reconnectExhaustedFlag = false
        }
        if (wasReconnect) {
            telemetry?.recordReconnectSuccess(lastBackoffDelayMs)
        }
        lastMessageAtMs = timeSource()
        startHeartbeat()
        outerListener?.onConnected()
        val domains = activeDomains.toSet()
        if (domains.isNotEmpty()) {
            onResubscribe?.invoke(domains)
        }
    }

    override fun onMessage(rawJson: String) {
        lastMessageAtMs = timeSource()
        outerListener?.onMessage(rawJson)
    }

    override fun onClosed(reason: String) {
        lastDisconnectReason = reason
        outerListener?.onClosed(reason)
        scheduleReconnect(reason)
    }

    override fun onError(error: TransportException) {
        lastDisconnectReason = error.message ?: "transport error"
        outerListener?.onError(error)
        scheduleReconnect(error.message ?: "transport error")
    }

    // ================================================================
    // Domain registration (clean resubscription)
    // ================================================================

    /** Registers [domain] so it is re-enabled after a successful reconnect. */
    fun registerDomain(domain: String) {
        activeDomains.add(domain)
    }

    /** Removes [domain] from the resubscription set. */
    fun unregisterDomain(domain: String) {
        activeDomains.remove(domain)
    }

    /** Snapshot of the domains currently registered for resubscription. */
    val activeDomainsSnapshot: Set<String> get() = activeDomains.toSet()

    // ================================================================
    // Diagnostics (test-observable resilience state)
    // ================================================================

    /** Number of backoff reconnect attempts scheduled since the last recovery. */
    val reconnectAttempts: Int get() = reconnectAttemptCount

    /** Whether the retry cap was reached and reconnection was given up. */
    val reconnectExhausted: Boolean get() = reconnectExhaustedFlag

    /** The most recent exponential backoff delay actually applied. */
    val lastAppliedBackoffMs: Long get() = lastBackoffDelayMs

    /** The URL the session last attempted to connect to. */
    val connectedUrl: String? get() = url

    /** The last disconnect/error reason observed from the underlying transport. */
    val disconnectReason: String? get() = lastDisconnectReason

    /** Age in ms of the last observed message, or [Long.MAX_VALUE] if none yet. */
    val lastMessageAgeMs: Long
        get() {
            val last = lastMessageAtMs
            if (last <= 0L) return Long.MAX_VALUE
            return (timeSource() - last).coerceAtLeast(0L)
        }

    /** Whether the heartbeat monitor is currently scheduled. */
    val heartbeatActive: Boolean get() = heartbeatFuture.get() != null

    /**
     * Active-channel heartbeat check (also invoked on the heartbeat schedule).
     * If the channel has been silent longer than [ReconnectPolicy.heartbeatTimeoutMs]
     * while still reported connected, the socket is force-disconnected to
     * trigger a clean reconnect cycle.
     */
    internal fun checkHeartbeat() {
        if (closed) return
        if (!transport.isConnected) return
        telemetry?.recordHeartbeat()
        val now = timeSource()
        val last = lastMessageAtMs
        if (last <= 0L) {
            lastMessageAtMs = now
            return
        }
        if (now - last >= policy.heartbeatTimeoutMs) {
            // Stale channel: force a clean reconnect cycle.
            lastMessageAtMs = now
            transport.disconnect()
        }
    }

    // ================================================================
    // Reconnect orchestration
    // ================================================================

    private fun scheduleReconnect(reason: String) {
        if (closed || !policy.enabled) return
        synchronized(lock) {
            if (reconnectPending) return
            if (reconnectAttemptCount >= policy.maxAttempts) {
                // Cap reached: notify exactly once per outage episode.
                if (exhaustedNotified) return
                exhaustedNotified = true
                reconnectExhaustedFlag = true
                telemetry?.recordReconnectExhausted()
                val hook = onReconnectExhausted
                if (hook != null) {
                    hook()
                }
                return
            }
            reconnectAttemptCount++
            reconnectPending = true
            val delay = backoff.delayMsForAttempt(reconnectAttemptCount)
            lastBackoffDelayMs = delay
            telemetry?.recordReconnectAttempt()
            val future = scheduler.schedule(
                { attemptReconnect(reason) },
                delay,
                TimeUnit.MILLISECONDS,
            )
            reconnectFuture.set(future)
        }
    }

    private fun attemptReconnect(reason: String) {
        val targetUrl = url
        if (targetUrl == null || closed) return
        synchronized(lock) {
            reconnectPending = false
            reconnectFuture.set(null)
        }
        transport.connect(targetUrl, this)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        if (!policy.enabled || policy.heartbeatIntervalMs <= 0L) return
        val future = scheduler.scheduleWithFixedDelay(
            { checkHeartbeat() },
            policy.heartbeatIntervalMs,
            policy.heartbeatIntervalMs,
            TimeUnit.MILLISECONDS,
        )
        heartbeatFuture.set(future)
    }

    private fun stopHeartbeat() {
        heartbeatFuture.getAndSet(null)?.cancel(false)
    }
}