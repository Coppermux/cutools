package com.coppermux.cutools.core.performance.models

/**
 * Immutable wrapper for a single genuine `Performance.metrics` metric value,
 * mirroring the CDP `MetricValue` `name` / `value` pair Chrome reports. Never
 * fabricated.
 */
data class MetricValueModel(
    val name: String,
    val value: Double,
)

/**
 * Immutable snapshot of a genuine `Performance.getMetrics` response or
 * `Performance.metrics` event. [metrics] is the list of metric values Chrome
 * reported and [timestamp] the local observation timestamp (ms epoch). No
 * metric is ever fabricated (No-Fake-Capability rule).
 */
data class PerformanceMetricsSnapshotModel(
    val metrics: List<MetricValueModel>,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Outcome of a `Performance` domain operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with a
 * value or resolves to an explicit [PerformanceError] (`Unsupported`,
 * `Unavailable`, `TargetDisconnected`, `ExecutionFailed`). No mock or
 * hardcoded metric state is ever produced (No-Fake-Capability rule).
 *
 * @param T The value carried on success.
 */
sealed class PerformanceActionResult<out T> {

    /** The performance operation succeeded, carrying [data]. */
    data class Success<out T>(val data: T) : PerformanceActionResult<T>()

    /** The performance operation failed, carrying an explicit [error]. */
    data class Failure(val error: PerformanceError) : PerformanceActionResult<Nothing>()
}

/**
 * Explicit, non-fabricated error for a failed `Performance` operation.
 */
sealed class PerformanceError {

    /** A `Performance` method is not available on the attached target. */
    object Unsupported : PerformanceError()

    /** The `Performance` domain cannot be reached (e.g., no bound session). */
    object Unavailable : PerformanceError()

    /** The attached target disconnected while the operation was in flight. */
    object TargetDisconnected : PerformanceError()

    /** The command failed (protocol error, parsing error, ...). */
    data class ExecutionFailed(val message: String) : PerformanceError()
}