package com.coppermux.cutools.ui.emulation

import com.coppermux.cutools.core.emulation.EmulationEngine
import com.coppermux.cutools.core.emulation.models.DeviceMetricsModel
import com.coppermux.cutools.core.emulation.models.EmulationActionResult
import com.coppermux.cutools.core.emulation.models.EmulationCapabilityStatus
import com.coppermux.cutools.core.emulation.models.EmulationError
import com.coppermux.cutools.core.emulation.models.MediaFeatureModel
import com.coppermux.cutools.core.emulation.models.ScreenOrientationModel
import com.coppermux.cutools.core.emulation.models.UserAgentOverrideModel
import com.coppermux.cutools.core.target.SessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for the Phase 9C Emulation panel.
 *
 * Binds to the shared [EmulationEngine] (Phase 9B) and exposes a single
 * [state] `StateFlow` consumed by the Compose UI. It issues genuine engine
 * commands — device metrics overrides, user-agent overrides, touch emulation,
 * emulated media features, and CPU throttling — and propagates explicit
 * engine-resolved outcomes into [EmulationUiState]. An engine failure
 * (Unsupported / Unavailable / ExecutionFailed / TargetDisconnected) surfaces
 * as an explicit [EmulationUiState.errorMessage] banner and a matching
 * [EmulationUiState.status], never as synthetic or fabricated emulation state
 * (No-Fake-Capability rule).
 *
 * [DEVICE_PRESETS] and [USER_AGENT_PRESETS] are UI convenience bundles that
 * map to genuine CDP `Emulation.setDeviceMetricsOverride` /
 * `Emulation.setUserAgentOverride` parameter payloads — they never pretend a
 * capability exists that the engine did not genuinely report.
 *
 * Threading: [state] is a thread-safe [StateFlow]; all engine reads are
 * non-blocking volatile reads, and engine callbacks are delivered on the
 * dispatcher's callback thread.
 */
class EmulationViewModel(
    private val engine: EmulationEngine,
) {

    private val _state = MutableStateFlow(EmulationUiState())
    val state: StateFlow<EmulationUiState> = _state

    /** The active attached target session, or null when detached. */
    @Volatile
    var attachedSession: SessionInfo? = null
        private set

    val isAttached: Boolean get() = attachedSession != null

    // ================================================================
    // Target lifecycle
    // ================================================================

    /** Binds the emulation engine to a newly-attached target session. */
    fun attachTarget(session: SessionInfo) {
        engine.bindSession(session)
        attachedSession = session
        publish(
            EmulationUiState(
                status = engine.capability ?: EmulationCapabilityStatus.UNAVAILABLE,
            )
        )
    }

    /** Detaches the emulation engine, clearing all target-scoped state. */
    fun detachTarget() {
        engine.detach()
        attachedSession = null
        publish(EmulationUiState())
    }

    /** Handles connection loss across the emulation engine. */
    fun onConnectionLost() {
        engine.onConnectionLost()
        attachedSession = null
        publish(EmulationUiState())
    }

    /** Releases the emulation engine's state (command-driven domain). */
    fun shutdown() {
        engine.shutdown()
    }

    // ================================================================
    // Commands
    // ================================================================

    /**
     * Dispatches a device metrics override via `Emulation.setDeviceMetricsOverride`
     * and records the genuinely-applied [preset] on success.
     */
    fun applyDevicePreset(
        preset: DeviceMetricsModel,
        callback: (EmulationActionResult<Unit>) -> Unit = {},
    ) {
        engine.setDeviceMetricsOverride(preset) { result ->
            when (result) {
                is EmulationActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = EmulationCapabilityStatus.SUPPORTED,
                            activeMetrics = preset,
                            errorMessage = null,
                        )
                    )
                }
                is EmulationActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: EmulationCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Restores native device metrics via `Emulation.clearDeviceMetricsOverride`,
     * clearing the active override on success.
     */
    fun clearDeviceMetrics(callback: (EmulationActionResult<Unit>) -> Unit = {}) {
        engine.clearDeviceMetricsOverride { result ->
            when (result) {
                is EmulationActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = EmulationCapabilityStatus.SUPPORTED,
                            activeMetrics = null,
                            errorMessage = null,
                        )
                    )
                }
                is EmulationActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: EmulationCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Dispatches a user-agent string and platform override via
     * `Emulation.setUserAgentOverride` and records the genuinely-applied
     * [ua] on success.
     */
    fun setUserAgent(
        ua: UserAgentOverrideModel,
        callback: (EmulationActionResult<Unit>) -> Unit = {},
    ) {
        engine.setUserAgentOverride(ua) { result ->
            when (result) {
                is EmulationActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = EmulationCapabilityStatus.SUPPORTED,
                            activeUserAgent = ua,
                            errorMessage = null,
                        )
                    )
                }
                is EmulationActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: EmulationCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Toggles touch emulation via `Emulation.setTouchEmulationEnabled` and
     * records the genuinely-applied [enabled] state on success.
     */
    fun toggleTouchEmulation(
        enabled: Boolean,
        callback: (EmulationActionResult<Unit>) -> Unit = {},
    ) {
        engine.setTouchEmulationEnabled(enabled) { result ->
            when (result) {
                is EmulationActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = EmulationCapabilityStatus.SUPPORTED,
                            touchEmulationEnabled = enabled,
                            errorMessage = null,
                        )
                    )
                }
                is EmulationActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: EmulationCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Dispatches emulated media type and feature overrides via
     * `Emulation.setEmulatedMedia` and records the genuinely-applied [media] and
     * [features] on success.
     */
    fun setMediaOverrides(
        media: String?,
        features: List<MediaFeatureModel>,
        callback: (EmulationActionResult<Unit>) -> Unit = {},
    ) {
        engine.setEmulatedMedia(media, features) { result ->
            when (result) {
                is EmulationActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = EmulationCapabilityStatus.SUPPORTED,
                            emulatedMedia = media,
                            activeMediaFeatures = features,
                            errorMessage = null,
                        )
                    )
                }
                is EmulationActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: EmulationCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Applies a CPU throttling multiplier via `Emulation.setCPUThrottlingRate`
     * and records the genuinely-applied [rate] on success.
     */
    fun setCPUThrottling(
        rate: Double,
        callback: (EmulationActionResult<Unit>) -> Unit = {},
    ) {
        engine.setCPUThrottlingRate(rate) { result ->
            when (result) {
                is EmulationActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = EmulationCapabilityStatus.SUPPORTED,
                            cpuThrottlingRate = rate,
                            errorMessage = null,
                        )
                    )
                }
                is EmulationActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: EmulationCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    // ================================================================
    // Error helpers
    // ================================================================

    /**
     * Maps an explicit [EmulationError] to a renderable banner string
     * (No-Fake-Capability: the banner is derived from the genuine engine
     * failure, never synthesized success).
     */
    fun describeError(error: EmulationError): String = when (error) {
        is EmulationError.Unsupported -> "Unsupported: the Emulation domain is not available on this target."
        is EmulationError.Unavailable -> "Unavailable: no attached target session."
        is EmulationError.TargetDisconnected -> "Failed: target disconnected."
        is EmulationError.ExecutionFailed -> "Failed: ${error.message.ifEmpty { "execution failed" }}"
    }

    private fun publish(next: EmulationUiState) {
        _state.value = next
    }

    companion object {
        /**
         * Convenience device metric presets mapped to genuine
         * `Emulation.setDeviceMetricsOverride` payloads (Mobile / Tablet /
         * Desktop viewports). These are parameter bundles only — applying one
         * still requires a genuine engine success.
         */
        val DEVICE_PRESETS: List<Pair<String, DeviceMetricsModel>> = listOf(
            "Mobile (390x844)" to DeviceMetricsModel(
                width = 390,
                height = 844,
                deviceScaleFactor = 3.0,
                mobile = true,
                screenOrientation = ScreenOrientationModel(type = "portraitPrimary", angle = 0),
            ),
            "Tablet (768x1024)" to DeviceMetricsModel(
                width = 768,
                height = 1024,
                deviceScaleFactor = 2.0,
                mobile = true,
                screenOrientation = ScreenOrientationModel(type = "portraitPrimary", angle = 0),
            ),
            "Desktop (1280x720)" to DeviceMetricsModel(
                width = 1280,
                height = 720,
                deviceScaleFactor = 1.0,
                mobile = false,
            ),
        )

        /**
         * Convenience user-agent presets mapped to genuine
         * `Emulation.setUserAgentOverride` payloads (Chrome Mobile / Chrome
         * Desktop). These are parameter bundles only — applying one still
         * requires a genuine engine success.
         */
        val USER_AGENT_PRESETS: List<Pair<String, UserAgentOverrideModel>> = listOf(
            "Chrome Mobile" to UserAgentOverrideModel(
                userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                platform = "Android",
            ),
            "Chrome Desktop" to UserAgentOverrideModel(
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                platform = "Linux",
            ),
        )

        /** The CPU throttling slowdown factors selectable in the UI. */
        val CPU_THROTTLE_RATES: List<Double> = listOf(1.0, 2.0, 4.0, 6.0)
    }
}