package com.coppermux.cutools.ui.emulation

import com.coppermux.cutools.core.emulation.models.DeviceMetricsModel
import com.coppermux.cutools.core.emulation.models.EmulationCapabilityStatus
import com.coppermux.cutools.core.emulation.models.MediaFeatureModel
import com.coppermux.cutools.core.emulation.models.UserAgentOverrideModel

/**
 * Immutable UI snapshot for the Emulation panel.
 *
 * Mirrors genuine engine truth only — [status] is derived from the
 * [com.coppermux.cutools.core.emulation.EmulationEngine] capability,
 * [activeMetrics] / [activeUserAgent] / [touchEmulationEnabled] /
 * [emulatedMedia] / [activeMediaFeatures] / [cpuThrottlingRate] hold the last
 * successfully-dispatched override state, and [errorMessage] carries an
 * explicit renderable error resolved from an engine failure. No mock or
 * synthetic emulation state is ever produced (No-Fake-Capability rule).
 *
 * @property status The explicit `Emulation` capability (SUPPORTED /
 *   UNSUPPORTED / UNAVAILABLE / FAILED).
 * @property activeMetrics The last device metrics override dispatched
 *   successfully, or null when native metrics are active.
 * @property activeUserAgent The last user-agent override dispatched
 *   successfully, or null when the native UA is active.
 * @property touchEmulationEnabled Whether touch emulation is currently active.
 * @property emulatedMedia The emulated media type (e.g. "screen", "print"),
 *   or null when no media override is active.
 * @property activeMediaFeatures The last media-feature overrides dispatched
 *   (e.g. prefers-color-scheme / prefers-reduced-motion).
 * @property cpuThrottlingRate The active CPU throttling slowdown factor
 *   (1.0 = no throttling, 6.0 = six times slower).
 * @property errorMessage A renderable banner string, or null when no error.
 */
data class EmulationUiState(
    val status: EmulationCapabilityStatus = EmulationCapabilityStatus.UNAVAILABLE,
    val activeMetrics: DeviceMetricsModel? = null,
    val activeUserAgent: UserAgentOverrideModel? = null,
    val touchEmulationEnabled: Boolean = false,
    val emulatedMedia: String? = null,
    val activeMediaFeatures: List<MediaFeatureModel> = emptyList(),
    val cpuThrottlingRate: Double = 1.0,
    val errorMessage: String? = null,
)