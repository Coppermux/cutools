package com.coppermux.cutools.core.profiling.models

/**
 * Immutable wrapper for a CDP `Profiler.ProfileNode`.
 *
 * Chrome's `Profiler` domain reports a sampled JavaScript CPU profile as a
 * forest of nodes. Each node is identified by its integer [id], carries the
 * sampled function name / script location, and lists its [children] by node id.
 * Only the fields Chrome actually supplies are surfaced; unknown protocol
 * fields are tolerated and do not cause a parsing failure.
 */
data class ProfileNodeModel(
    val id: Long,
    val functionName: String? = null,
    val scriptId: String? = null,
    val url: String? = null,
    val lineNumber: Int? = null,
    val columnNumber: Int? = null,
    val hitCount: Int? = null,
    val children: List<Long> = emptyList(),
)

/**
 * Immutable wrapper for a CDP `Profiler.Profile`.
 *
 * [nodes] is the profile's call-tree nodes; [samples] is the flat sequence of
 * leaf node ids captured at each sampling tick, and [timeDeltas] holds the
 * elapsed time between consecutive samples (Chrome's profiler clock units).
 * [startTime] / [endTime] delimit the recording window.
 *
 * Self-time / total-time are never fabricated here — they are derived from the
 * genuine [samples] / [timeDeltas] by
 * [com.coppermux.cutools.core.profiling.CpuProfileAnalyzer]
 * (No-Fake-Capability rule).
 */
data class CpuProfileModel(
    val nodes: List<ProfileNodeModel> = emptyList(),
    val startTime: Double? = null,
    val endTime: Double? = null,
    val samples: List<Long> = emptyList(),
    val timeDeltas: List<Double> = emptyList(),
)

/**
 * Lifecycle state of a `Profiler` sampling session on the attached target.
 */
enum class CpuProfilerStatus {
    /** No profiling session is active. */
    IDLE,

    /** `Profiler.start` succeeded; samples are being collected. */
    PROFILING,

    /** Profiling has stopped and the profile is being processed. */
    PROCESSING,

    /** Profiling is complete; the final call tree is available. */
    STOPPED,
}

/**
 * Outcome of a `Profiler` operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with a
 * value or resolves to an explicit [CpuProfilerError] (`Unsupported`,
 * `Unavailable`, `ExecutionFailed`, `ProfilingInProgress`). No mock or hardcoded
 * profile data is ever produced (No-Fake-Capability rule).
 */
sealed interface CpuProfilerActionResult<out T> {

    /** The profiler operation succeeded, carrying [value]. */
    data class Success<T>(val value: T) : CpuProfilerActionResult<T>

    /** The profiler operation failed, carrying an explicit [error]. */
    data class Failure(val error: CpuProfilerError) : CpuProfilerActionResult<Nothing>
}

/**
 * Explicit, non-fabricated error for a failed `Profiler` operation.
 */
sealed interface CpuProfilerError {

    /** The `Profiler` method is not available on the attached target. */
    data class Unsupported(val reason: String) : CpuProfilerError

    /** The `Profiler` domain cannot be reached (e.g., no bound session). */
    data class Unavailable(val detail: String) : CpuProfilerError

    /** The profiler command failed (protocol error, connection loss, ...). */
    data class ExecutionFailed(val cause: Throwable) : CpuProfilerError

    /** A sampling session is already active and cannot be started again. */
    data class ProfilingInProgress(val detail: String) : CpuProfilerError
}

/**
 * Derived call-tree timing for a single [ProfileNodeModel.id].
 *
 * [selfTime] is the time attributed directly to the node (its own samples) and
 * [totalTime] additionally includes the time of all descendant nodes — both in
 * the same clock units as the profile's [CpuProfileModel.timeDeltas]. Derived
 * purely from genuine Chrome sample data.
 */
data class CpuNodeTiming(
    val nodeId: Long,
    val selfTime: Double,
    val totalTime: Double,
)

/**
 * The processed result of a completed CPU profiling session.
 *
 * [duration] is the total recording window derived from
 * [CpuProfileModel.startTime] / [CpuProfileModel.endTime]. [timings] holds the
 * per-node self-time / total-time computed from [CpuProfileModel.samples] and
 * [CpuProfileModel.timeDeltas]. All values originate from genuine Chrome data.
 */
data class CpuProfileTiming(
    val sessionId: String? = null,
    val status: CpuProfilerStatus = CpuProfilerStatus.IDLE,
    val duration: Double = 0.0,
    val profile: CpuProfileModel = CpuProfileModel(),
    val timings: List<CpuNodeTiming> = emptyList(),
) {
    val nodeCount: Int get() = profile.nodes.size
}
