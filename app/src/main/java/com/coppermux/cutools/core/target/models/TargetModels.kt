package com.coppermux.cutools.core.target.models

/**
 * Immutable model of a Chrome debugging target discovered via the CDP `Target`
 * domain.
 *
 * All fields originate strictly from Chrome — none are fabricated. [targetId]
 * and [type] are mandatory wire fields; [title] / [url] / [attached] /
 * [openerId] / [browserContextId] fall back to safe defaults when absent
 * (No-Fake-Capability rule).
 */
data class TargetInfoModel(
    val targetId: String,
    val type: String,
    val title: String = "",
    val url: String = "",
    val attached: Boolean = false,
    val openerId: String? = null,
    val browserContextId: String? = null,
)

/**
 * Availability of the `Target` CDP domain for the bound connection.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class TargetCapabilityStatus {
    /** `Target` commands/notifications are functional on the attached connection. */
    SUPPORTED,

    /** A `Target` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `Target` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** A `Target` command failed with an error. */
    FAILED,
}

/**
 * Outcome of a `Target` domain operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with a
 * value or resolves to an explicit [TargetError] (`Unsupported`, `Unavailable`,
 * `TargetDisconnected`, `ExecutionFailed`). No mock or hardcoded target state is
 * ever produced (No-Fake-Capability rule).
 *
 * @param T The value carried on success (e.g. a session id or unit).
 */
sealed class TargetActionResult<out T> {

    /** The target operation succeeded, carrying [data]. */
    data class Success<out T>(val data: T) : TargetActionResult<T>()

    /** The target operation failed, carrying an explicit [error]. */
    data class Failure(val error: TargetError) : TargetActionResult<Nothing>()
}

/**
 * Explicit, non-fabricated error for a failed `Target` operation.
 */
sealed class TargetError {

    /** A `Target` method is not available on the attached target. */
    object Unsupported : TargetError()

    /** The `Target` domain cannot be reached (e.g., no bound session). */
    object Unavailable : TargetError()

    /** The attached target disconnected while the operation was in flight. */
    object TargetDisconnected : TargetError()

    /** The command failed (protocol error, parsing error, ...). */
    data class ExecutionFailed(val message: String) : TargetError()
}