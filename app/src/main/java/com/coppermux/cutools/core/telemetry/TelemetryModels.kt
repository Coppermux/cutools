package com.coppermux.cutools.core.telemetry

import com.coppermux.cutools.core.capability.CapabilityStatus

/**
 * A single measured CDP command execution latency sample.
 *
 * [method] is the CDP method (e.g., `Runtime.evaluate`), [durationNanos] the
 * round-trip latency observed by the engine, and [startedAtEpochMillis] the
 * wall-clock start time for windowing/ordering analysis.
 */
data class CommandLatencySample(
    val method: String,
    val durationNanos: Long,
    val startedAtEpochMillis: Long,
)

/**
 * Aggregated command latency metrics for a single CDP method.
 *
 * Aggregation is deterministic: [count] / [totalNanos] / [minNanos] / [maxNanos]
 * accumulate monotonically, and [meanNanos] is always derived from those
 * genuine counters — never from a fabricated sample.
 */
data class CommandLatencyMetrics(
    val method: String,
    val count: Long = 0L,
    val totalNanos: Long = 0L,
    val minNanos: Long = 0L,
    val maxNanos: Long = 0L,
) {
    val meanNanos: Double get() = if (count == 0L) 0.0 else totalNanos.toDouble() / count
}

/**
 * A capability fallback occurrence recorded by [TelemetryEngine].
 *
 * A fallback is a genuine engine outcome — the `UNSUPPORTED` / `UNAVAILABLE`
 * (or `FAILED`) status resolved by a real CDP command against a real target,
 * never a fabricated success. [status] carries the exact
 * [CapabilityStatus] the engine resolved so the audit can distinguish
 * "domain absent on this target" from "domain temporarily unreachable".
 */
data class CapabilityFallbackRecord(
    val engine: String,
    val method: String,
    val status: CapabilityStatus,
    val occurredAtEpochMillis: Long,
)

/**
 * Aggregated fallback counts for a single engine.
 *
 * Counts are bucketed by fallback kind (`UNSUPPORTED`, `UNAVAILABLE`, `FAILED`)
 * and [total] is the sum of the three genuine counters.
 */
data class CapabilityFallbackStats(
    val engine: String,
    val unsupportedCount: Long = 0L,
    val unavailableCount: Long = 0L,
    val failedCount: Long = 0L,
) {
    val total: Long get() = unsupportedCount + unavailableCount + failedCount
}

/**
 * Transport auto-reconnect telemetry.
 *
 * [reconnectAttempts] counts every backoff-retry scheduled after a socket drop;
 * [successfulReconnects] counts the re-establishments that completed;
 * [exhaustedCount] counts the times the retry cap was reached and the channel
 * was given up on. [lastBackoffDelayMs] is the most recent exponential backoff
 * delay actually applied.
 */
data class TransportReconnectStats(
    val reconnectAttempts: Long = 0L,
    val successfulReconnects: Long = 0L,
    val exhaustedCount: Long = 0L,
    val lastBackoffDelayMs: Long = 0L,
    val lastReconnectAtEpochMillis: Long = 0L,
)

/**
 * Event streaming throughput counters.
 *
 * [totalEvents] is the monotonic count of every CDP event observed since the
 * engine was enabled (or last [TelemetryEngine.reset]); [lastWindowCount] is the
 * number of events observed in the most recently rotated window, allowing
 * bursts to be measured without unbounded growth. [eventCountsByMethod] breaks
 * the total down by CDP event method (e.g., `Network.requestWillBeSent`).
 */
data class EventThroughput(
    val totalEvents: Long = 0L,
    val lastWindowCount: Long = 0L,
    val eventCountsByMethod: Map<String, Long> = emptyMap(),
)

/**
 * Immutable, thread-safe snapshot of all telemetry state exposed via
 * [TelemetryEngine.snapshot] / [TelemetryEngine.state] `StateFlow`.
 *
 * Every field is derived strictly from recorded metrics — no value is
 * synthesized. [enabled] reflects the current opt-in/opt-out toggle.
 */
data class TelemetrySnapshot(
    val enabled: Boolean,
    val commandLatency: Map<String, CommandLatencyMetrics>,
    val capabilityFallbacks: Map<String, CapabilityFallbackStats>,
    val fallbackEventLog: List<CapabilityFallbackRecord>,
    val reconnect: TransportReconnectStats,
    val eventThroughput: EventThroughput,
    val heartbeatCount: Long = 0L,
) {
    companion object {
        /** An empty snapshot with the given opt-in state. */
        fun empty(enabled: Boolean = true): TelemetrySnapshot = TelemetrySnapshot(
            enabled = enabled,
            commandLatency = emptyMap(),
            capabilityFallbacks = emptyMap(),
            fallbackEventLog = emptyList(),
            reconnect = TransportReconnectStats(),
            eventThroughput = EventThroughput(),
            heartbeatCount = 0L,
        )
    }
}