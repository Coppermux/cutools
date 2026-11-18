package com.coppermux.cutools.core.telemetry

import com.coppermux.cutools.core.capability.CapabilityStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in runtime telemetry & capability fallback auditor.
 *
 * Phase 15 — Field Instrumentation: collects internal engine metrics — command
 * latencies, event streaming throughput, CDP transport reconnect counts, and
 * `CapabilityStatus` fallback occurrences (`UNSUPPORTED` / `UNAVAILABLE` /
 * `FAILED`) — without any external network side-effect. Every metric is
 * recorded strictly from genuine engine/transport outcomes (No-Fake-Capability):
 * a fallback is only ever counted when the engine actually resolved an
 * unsupported/unavailable/failed status against the target.
 *
 * Thread safety: all mutable aggregation state is guarded by a single lock and
 * [snapshot] / [state] values are built under that lock, so concurrent recorders
 * (event callbacks, reconnect scheduler threads) never tear a snapshot.
 *
 * Opt-in/opt-out: [setEnabled] toggles collection. While disabled every
 * `record*` call is a no-op, so opting out halts metric collection without
 * touching any other subsystem.
 */
class TelemetryEngine(
    initialEnabled: Boolean = false,
    /** Maximum number of fallback records retained in the bounded event log. */
    private val maxFallbackLogSize: Int = 256,
) {

    private val enabledFlag = AtomicBoolean(initialEnabled)

    /** Monotonic counters kept outside the lock for cheap high-frequency reads. */
    private val heartbeatCounter = AtomicLong(0L)
    private val reconnectAttemptCounter = AtomicLong(0L)
    private val reconnectSuccessCounter = AtomicLong(0L)
    private val reconnectExhaustedCounter = AtomicLong(0L)
    private val totalEventCounter = AtomicLong(0L)

    // All remaining aggregation state is guarded by this lock.
    private val lock = Any()
    private val latencies = LinkedHashMap<String, CommandLatencyMetrics>()
    private val fallbackStats = LinkedHashMap<String, CapabilityFallbackStats>()
    private val fallbackLog = ArrayDeque<CapabilityFallbackRecord>()
    private val eventCountsByMethod = LinkedHashMap<String, Long>()
    private var windowCount: Long = 0L
    private var lastWindowCount: Long = 0L
    private var lastBackoffDelayMs: Long = 0L
    private var lastReconnectAtEpochMillis: Long = 0L

    private val _state = MutableStateFlow(TelemetrySnapshot.empty(initialEnabled))
    val state: StateFlow<TelemetrySnapshot> = _state

    /** Whether telemetry collection is currently enabled (opt-in). */
    val enabled: Boolean get() = enabledFlag.get()

    /**
     * Toggles opt-in telemetry collection.
     *
     * While disabled, every `record*` call is a no-op. Existing metrics are
     * retained (use [reset] to clear), and a fresh snapshot is emitted.
     */
    fun setEnabled(enabled: Boolean) {
        val changed = enabledFlag.getAndSet(enabled) != enabled
        if (changed) {
            emit()
        }
    }

    // ================================================================
    // Command latency
    // ================================================================

    /**
     * Records a genuine command round-trip latency for [method]. Negative or
     * zero durations are ignored (they carry no measurement value).
     */
    fun recordCommandLatency(method: String, durationNanos: Long) {
        if (!enabledFlag.get() || durationNanos <= 0L) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val current = latencies[method]
            latencies[method] = CommandLatencyMetrics(
                method = method,
                count = (current?.count ?: 0L) + 1L,
                totalNanos = (current?.totalNanos ?: 0L) + durationNanos,
                minNanos = if (current == null) durationNanos else minOf(current.minNanos, durationNanos),
                maxNanos = if (current == null) durationNanos else maxOf(current.maxNanos, durationNanos),
            )
        }
        emit()
    }

    // ================================================================
    // Event streaming throughput
    // ================================================================

    /**
     * Records a CDP event received. [method] (when supplied) is bucketed per
     * method; the total and current-window counters always increment.
     */
    fun recordEventReceived(method: String? = null) {
        if (!enabledFlag.get()) return
        totalEventCounter.incrementAndGet()
        synchronized(lock) {
            windowCount += 1L
            if (method != null) {
                eventCountsByMethod[method] = (eventCountsByMethod[method] ?: 0L) + 1L
            }
        }
        emit()
    }

    /**
     * Rotates the streaming-throughput window: the current window count is
     * frozen into [EventThroughput.lastWindowCount] and the window resets to
     * zero. This lets a caller measure a burst without unbounded growth.
     */
    fun rotateEventWindow() {
        if (!enabledFlag.get()) return
        synchronized(lock) {
            lastWindowCount = windowCount
            windowCount = 0L
        }
        emit()
    }

    // ================================================================
    // Transport reconnect
    // ================================================================

    /** Records that a backoff reconnect attempt was scheduled. */
    fun recordReconnectAttempt() {
        if (!enabledFlag.get()) return
        reconnectAttemptCounter.incrementAndGet()
        emit()
    }

    /**
     * Records a successful socket re-establishment, retaining the [backoffDelayMs]
     * that was actually applied before the retry.
     */
    fun recordReconnectSuccess(backoffDelayMs: Long = 0L) {
        if (!enabledFlag.get()) return
        reconnectSuccessCounter.incrementAndGet()
        synchronized(lock) {
            lastBackoffDelayMs = backoffDelayMs
            lastReconnectAtEpochMillis = System.currentTimeMillis()
        }
        emit()
    }

    /** Records that the retry cap was reached and reconnection was given up. */
    fun recordReconnectExhausted() {
        if (!enabledFlag.get()) return
        reconnectExhaustedCounter.incrementAndGet()
        emit()
    }

    // ================================================================
    // Capability fallback audit
    // ================================================================

    /**
     * Records a genuine capability fallback for [engine] / [method].
     *
     * Only `UNSUPPORTED`, `UNAVAILABLE`, and `FAILED` statuses are fallbacks;
     * `SUPPORTED` and `EXPERIMENTAL` statuses are ignored (they are not
     * fallbacks). The record is appended to the bounded fallback event log.
     */
    fun recordCapabilityFallback(engine: String, method: String, status: CapabilityStatus) {
        if (!enabledFlag.get()) return
        val (kind, increments) = when (status) {
            is CapabilityStatus.Unsupported -> FallbackKind.UNSUPPORTED to true
            is CapabilityStatus.Unavailable -> FallbackKind.UNAVAILABLE to true
            is CapabilityStatus.Failed -> FallbackKind.FAILED to true
            else -> null to false
        }
        if (!increments || kind == null) return
        synchronized(lock) {
            val current = fallbackStats[engine] ?: CapabilityFallbackStats(engine)
            fallbackStats[engine] = when (kind) {
                FallbackKind.UNSUPPORTED -> current.copy(unsupportedCount = current.unsupportedCount + 1L)
                FallbackKind.UNAVAILABLE -> current.copy(unavailableCount = current.unavailableCount + 1L)
                FallbackKind.FAILED -> current.copy(failedCount = current.failedCount + 1L)
            }
            val record = CapabilityFallbackRecord(
                engine = engine,
                method = method,
                status = status,
                occurredAtEpochMillis = System.currentTimeMillis(),
            )
            fallbackLog.addLast(record)
            while (fallbackLog.size > maxFallbackLogSize) {
                fallbackLog.removeFirst()
            }
        }
        emit()
    }

    // ================================================================
    // Heartbeat
    // ================================================================

    /** Records a transport heartbeat observation. */
    fun recordHeartbeat() {
        if (!enabledFlag.get()) return
        heartbeatCounter.incrementAndGet()
        emit()
    }

    // ================================================================
    // Snapshots & audit
    // ================================================================

    /**
     * Returns a consistent, immutable snapshot of all recorded telemetry.
     * The snapshot is built under the aggregation lock, so concurrent writers
     * never produce a torn view.
     */
    fun snapshot(): TelemetrySnapshot = synchronized(lock) {
        TelemetrySnapshot(
            enabled = enabledFlag.get(),
            commandLatency = latencies.toMap(),
            capabilityFallbacks = fallbackStats.toMap(),
            fallbackEventLog = fallbackLog.toList(),
            reconnect = TransportReconnectStats(
                reconnectAttempts = reconnectAttemptCounter.get(),
                successfulReconnects = reconnectSuccessCounter.get(),
                exhaustedCount = reconnectExhaustedCounter.get(),
                lastBackoffDelayMs = lastBackoffDelayMs,
                lastReconnectAtEpochMillis = lastReconnectAtEpochMillis,
            ),
            eventThroughput = EventThroughput(
                totalEvents = totalEventCounter.get(),
                lastWindowCount = lastWindowCount,
                eventCountsByMethod = eventCountsByMethod.toMap(),
            ),
            heartbeatCount = heartbeatCounter.get(),
        )
    }

    /**
     * Capability fallback audit: aggregated `UNSUPPORTED` / `UNAVAILABLE` /
     * `FAILED` counts per engine that recorded at least one fallback.
     */
    fun fallbackAudit(): List<CapabilityFallbackStats> = synchronized(lock) {
        fallbackStats.values.toList()
    }

    /**
     * Clears every recorded metric, fallback record, and reconnect counter,
     * preserving the current opt-in toggle and the bounded event log capacity.
     */
    fun reset() {
        synchronized(lock) {
            latencies.clear()
            fallbackStats.clear()
            fallbackLog.clear()
            eventCountsByMethod.clear()
            windowCount = 0L
            lastWindowCount = 0L
            lastBackoffDelayMs = 0L
            lastReconnectAtEpochMillis = 0L
        }
        reconnectAttemptCounter.set(0L)
        reconnectSuccessCounter.set(0L)
        reconnectExhaustedCounter.set(0L)
        totalEventCounter.set(0L)
        heartbeatCounter.set(0L)
        emit()
    }

    // ================================================================
    // Internal
    // ================================================================

    private fun emit() {
        _state.value = snapshot()
    }

    private enum class FallbackKind {
        UNSUPPORTED,
        UNAVAILABLE,
        FAILED,
    }
}