package com.coppermux.cutools.core.profiling.models

/**
 * Raw CDP `TraceEvent` payload as reported by the `Tracing` domain.
 *
 * Chrome streams trace events as a flat array in `Tracing.dataCollected`
 * (`params.value`) and in the final `Tracing.end` response. Each event carries
 * a name, a phase ([ph]), a category string ([cat]), and timing fields. All
 * timing fields are in microseconds (Chrome's trace clock units). Unknown
 * fields are tolerated; only the typed fields below are surfaced.
 */
data class TraceEvent(
    val name: String? = null,
    val cat: String? = null,
    val ph: String? = null,
    val ts: Long? = null,
    val dur: Long? = null,
    val args: Map<String, Any?>? = null,
    val pid: Long? = null,
    val tid: Long? = null,
)

/**
 * Trace event category filter used to slice raw trace events into logical
 * performance buckets.
 *
 * Each constant carries the wire category string Chrome emits in `TraceEvent.cat`
 * for the corresponding class of work. Unrecognized wire values resolve to
 * [OTHER]; no category is ever fabricated (No-Fake-Capability rule).
 */
enum class TracingCategory(val wireValue: String) {
    /** Top-level timeline work (long tasks, run tasks). */
    TIMELINE("toplevel"),

    /** JavaScript / V8 execution. */
    V8("v8"),

    /** Blink rendering, layout, and paint. */
    BLINK_RENDERING("blink_rendering"),

    /** User Timing marks & measures (`performance.mark` / `measure`). */
    USER_TIMING("user_timing"),

    /** Anything that does not map to a known tracing category. */
    OTHER("other");

    companion object {
        /** Maps a CDP wire category string to its [TracingCategory], defaulting to [OTHER]. */
        fun fromWire(value: String?): TracingCategory =
            entries.firstOrNull { it.wireValue == value } ?: OTHER
    }
}

/**
 * Lifecycle state of a `Tracing` session on the currently attached target.
 */
enum class TracingStatus {
    /** No tracing session is active. */
    IDLE,

    /** `Tracing.start` succeeded; trace events are being streamed in. */
    RECORDING,

    /** Tracing has ended and the buffered events are being classified. */
    PROCESSING,

    /** Tracing is complete; the final classified timeline is available. */
    STOPPED,
}

/**
 * Outcome of a `Tracing` operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with a
 * value or resolves to an explicit [TracingError] (`Unsupported`, `Unavailable`,
 * `ExecutionFailed`, `RecordingInProgress`). No mock or hardcoded trace data is
 * ever produced (No-Fake-Capability rule).
 */
sealed interface TracingActionResult<out T> {

    /** The tracing operation succeeded, carrying [value]. */
    data class Success<T>(val value: T) : TracingActionResult<T>

    /** The tracing operation failed, carrying an explicit [error]. */
    data class Failure(val error: TracingError) : TracingActionResult<Nothing>
}

/**
 * Explicit, non-fabricated error for a failed `Tracing` operation.
 */
sealed interface TracingError {

    /** The `Tracing` method is not available on the attached target. */
    data class Unsupported(val reason: String) : TracingError

    /** The `Tracing` domain cannot be reached (e.g., no bound session). */
    data class Unavailable(val detail: String) : TracingError

    /** The tracing command failed (protocol error, connection loss, ...). */
    data class ExecutionFailed(val cause: Throwable) : TracingError

    /** A recording is already active and cannot be started again. */
    class RecordingInProgress : TracingError
}

/**
 * A single classified timing block extracted from the trace stream.
 *
 * [category] is the [TracingCategory] the originating event was sliced into,
 * [startMicros]/[durationMicros] are in trace-clock microseconds.
 */
data class TraceRecord(
    val name: String,
    val category: TracingCategory,
    val startMicros: Long,
    val durationMicros: Long,
)

/**
 * A User Timing mark emitted by `performance.mark(...)` / `performance.measure(...)`.
 */
data class UserTimingMark(
    val name: String,
    val tsMicros: Long,
)

/**
 * The classified performance timeline assembled from a completed trace session.
 *
 * [eventCount] is the total number of raw `TraceEvent`s aggregated across every
 * streamed chunk plus the final `Tracing.end` flush. The metric collections are
 * derived from those events by [com.coppermux.cutools.core.profiling.TraceAnalyzer]
 * (long tasks > 50ms, rendering/layout/paint blocks, script execution, user
 * timing marks). All values originate from genuine Chrome trace data.
 */
data class TraceTimeline(
    val sessionId: String? = null,
    val status: TracingStatus = TracingStatus.IDLE,
    val eventCount: Int = 0,
    val longTasks: List<TraceRecord> = emptyList(),
    val renderingBlocks: List<TraceRecord> = emptyList(),
    val layoutBlocks: List<TraceRecord> = emptyList(),
    val paintBlocks: List<TraceRecord> = emptyList(),
    val scriptExecutions: List<TraceRecord> = emptyList(),
    val userTimingMarks: List<UserTimingMark> = emptyList(),
    val startedAtMicros: Long = 0,
    val endedAtMicros: Long = 0,
)