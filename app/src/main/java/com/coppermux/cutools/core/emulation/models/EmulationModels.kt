package com.coppermux.cutools.core.emulation.models

/**
 * Immutable wrapper for a CDP `Emulation.ScreenOrientation`, mirroring the
 * genuine `type` and `angle` Chrome accepts (e.g. "portraitPrimary",
 * "landscapeSecondary"). Never fabricated.
 */
data class ScreenOrientationModel(
    val type: String,
    val angle: Int,
)

/**
 * Immutable wrapper for a CDP `Emulation.setDeviceMetricsOverride` parameter
 * payload. Only the mandatory `width`, `height`, `deviceScaleFactor`, and
 * `mobile` fields are required; every optional field (scale, screenWidth,
 * screenHeight, positionX, positionY, dontSetVisibleSize, screenOrientation)
 * is supplied only when a genuine value is present (No-Fake-Capability rule).
 */
data class DeviceMetricsModel(
    val width: Int,
    val height: Int,
    val deviceScaleFactor: Double,
    val mobile: Boolean,
    val scale: Double? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val positionX: Int? = null,
    val positionY: Int? = null,
    val dontSetVisibleSize: Boolean? = null,
    val screenOrientation: ScreenOrientationModel? = null,
)

/**
 * Immutable wrapper for a CDP `Emulation.setUserAgentOverride` payload.
 * Only the mandatory `userAgent` is required; `acceptLanguage` and `platform`
 * are optional and only serialized when supplied.
 */
data class UserAgentOverrideModel(
    val userAgent: String,
    val acceptLanguage: String? = null,
    val platform: String? = null,
)

/**
 * Immutable wrapper for a single `Emulation.setEmulatedMedia` media feature,
 * mirroring the genuine `name`/`value` pair Chrome accepts (e.g.
 * `prefers-color-scheme`/`dark`). Never fabricated.
 */
data class MediaFeatureModel(
    val name: String,
    val value: String,
)

/**
 * Outcome of an `Emulation` domain operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with a
 * value or resolves to an explicit [EmulationError] (`Unsupported`,
 * `Unavailable`, `ExecutionFailed`, `TargetDisconnected`). No mock or hardcoded
 * emulation state is ever produced (No-Fake-Capability rule).
 *
 * @param T The value carried on success.
 */
sealed class EmulationActionResult<out T> {

    /** The operation succeeded, carrying [data]. */
    data class Success<out T>(val data: T) : EmulationActionResult<T>()

    /** The operation failed, carrying an explicit [error]. */
    data class Failure(val error: EmulationError) : EmulationActionResult<Nothing>()
}

/**
 * Explicit, non-fabricated error for a failed `Emulation` operation.
 */
sealed class EmulationError {

    /** An `Emulation` method is not available on the attached target. */
    object Unsupported : EmulationError()

    /** The `Emulation` domain cannot be reached (e.g., no bound session). */
    object Unavailable : EmulationError()

    /** The attached target disconnected while the operation was in flight. */
    object TargetDisconnected : EmulationError()

    /** The command failed (protocol error, parsing error, ...). */
    data class ExecutionFailed(val message: String) : EmulationError()
}
