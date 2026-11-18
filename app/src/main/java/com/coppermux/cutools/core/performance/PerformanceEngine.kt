package com.coppermux.cutools.core.performance

import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.performance.models.PerformanceActionResult
import com.coppermux.cutools.core.performance.models.PerformanceCapabilityStatus
import com.coppermux.cutools.core.performance.models.PerformanceError
import com.coppermux.cutools.core.performance.models.PerformanceMetricsSnapshotModel
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates high-precision timeline metric collection for the currently
 * attached page target via the CDP `Performance` domain.
 *
 * Phase 10 — Performance Engine:
 * - [enable] / [disable] manage `Performance` domain notifications, with an
 *   optional [timeDomain] serialized only when supplied.
 * - [getMetrics] requests the current metrics via `Performance.getMetrics` and
 *   publishes the genuine [PerformanceMetricsSnapshotModel] to [metrics].
 * - The engine registers a `Performance.metrics` listener, ingesting genuine
 *   metric snapshots into a real-time [metrics] `StateFlow` — never fabricating
 *   a metric value (No-Fake-Capability rule).
 * - Session attach/detach lifecycle resets all target-scoped state and
 *   re-binds the event subscription; a generation counter rejects stale async
 *   results / events delivered after detach / re-bind.
 * - Protocol failures, missing sessions, unavailable methods, and connection
 *   loss each resolve to an explicit [PerformanceActionResult.Failure] carrying
 *   a [PerformanceError] (`Unsupported` / `Unavailable` / `TargetDisconnected`
 *   / `ExecutionFailed`).
 *
 * Threading: all shared state is `@Volatile`; [metrics] is a thread-safe
 * [StateFlow] so concurrent event streams never lose an update. All Chrome
 * communication reuses the shared [CommandDispatcher] — no second transport or
 * dispatcher is created.
 */
class PerformanceEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `Performance` domain capability for the current session/target. */
    @Volatile
    var capability: PerformanceCapabilityStatus? = null
        private set

    /** Whether `Performance` domain notifications are currently enabled. */
    @Volatile
    var enabled: Boolean = false
        private set

    /** Real-time `Performance.metrics` snapshot, or null when none observed. */
    private val _metrics = MutableStateFlow<PerformanceMetricsSnapshotModel?>(null)
    val metrics: StateFlow<PerformanceMetricsSnapshotModel?> = _metrics

    /** Guards stale async results/events after detach / re-bind. */
    private val generation = AtomicInteger(0)

    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session, resets prior target state, and enables the domain. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        resetState()
        clearSubscriptions()
        registerSubscriptions()
        sendEnable()
    }

    /** Detaches, clearing all target-scoped performance state and subscriptions. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        resetState()
        clearSubscriptions()
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    /** Releases all event subscriptions (no listener leaks). */
    fun shutdown() {
        clearSubscriptions()
    }

    // ================================================================
    // Domain lifecycle
    // ================================================================

    /**
     * Enables `Performance` domain metrics via `Performance.enable`. A genuine
     * [timeDomain] is serialized only when supplied. Protocol failures resolve
     * to an explicit [PerformanceActionResult.Failure].
     */
    fun enable(timeDomain: String? = null, callback: (PerformanceActionResult<Unit>) -> Unit = {}) {
        sendEnable(timeDomain, callback)
    }

    /**
     * Disables `Performance` domain metrics via `Performance.disable`.
     */
    fun disable(callback: (PerformanceActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = PerformanceCapabilityStatus.UNAVAILABLE
            callback(PerformanceActionResult.Failure(PerformanceError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Performance.disable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    enabled = false
                    callback(PerformanceActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(PerformanceActionResult.Failure(classifyFailure(result.error, "Performance.disable")))
                }
            }
        }
    }

    /**
     * Requests the current performance metrics via `Performance.getMetrics`. On
     * success the genuine snapshot is published to [metrics] and resolved; a
     * non-object result resolves to an explicit [PerformanceError.ExecutionFailed].
     */
    fun getMetrics(callback: (PerformanceActionResult<PerformanceMetricsSnapshotModel>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = PerformanceCapabilityStatus.UNAVAILABLE
            callback(PerformanceActionResult.Failure(PerformanceError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Performance.getMetrics",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val parsed = PerformanceParser.parseSnapshot(result.response.result)
                    if (parsed == null) {
                        capability = PerformanceCapabilityStatus.FAILED
                        callback(PerformanceActionResult.Failure(PerformanceError.ExecutionFailed("Performance.getMetrics returned a non-object result")))
                    } else {
                        capability = PerformanceCapabilityStatus.SUPPORTED
                        _metrics.value = parsed
                        callback(PerformanceActionResult.Success(parsed))
                    }
                }
                is CommandResult.Failure -> {
                    callback(PerformanceActionResult.Failure(classifyFailure(result.error, "Performance.getMetrics")))
                }
            }
        }
    }

    private fun sendEnable(timeDomain: String? = null, callback: (PerformanceActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = PerformanceCapabilityStatus.UNAVAILABLE
            callback(PerformanceActionResult.Failure(PerformanceError.Unavailable))
            return
        }
        val params = timeDomain?.let { mapOf("timeDomain" to it) }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Performance.enable",
            params = params,
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = PerformanceCapabilityStatus.SUPPORTED
                    enabled = true
                    callback(PerformanceActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(PerformanceActionResult.Failure(classifyFailure(result.error, "Performance.enable")))
                }
            }
        }
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("Performance.metrics") { event -> onMetrics(event) }
    }

    /** Applies a genuine `Performance.metrics` update if it belongs to the active session. */
    private fun onMetrics(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        val snapshot = PerformanceParser.parseSnapshot(event.params) ?: return
        _metrics.value = snapshot
    }

    // ================================================================
    // Error mapping & state helpers
    // ================================================================

    /**
     * Maps a CDP command failure to an explicit [PerformanceError]. A
     * method-not-found error (`-32601`) is [PerformanceError.Unsupported]; a
     * connection failure is [PerformanceError.TargetDisconnected]; everything
     * else is [PerformanceError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): PerformanceError {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = PerformanceCapabilityStatus.UNSUPPORTED
            return PerformanceError.Unsupported
        }
        if (error is ConnectionException) {
            capability = PerformanceCapabilityStatus.UNAVAILABLE
            return PerformanceError.TargetDisconnected
        }
        capability = PerformanceCapabilityStatus.FAILED
        return PerformanceError.ExecutionFailed(message.ifEmpty { "Unknown performance error" })
    }

    private fun resetState() {
        capability = null
        enabled = false
        _metrics.value = null
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }
}