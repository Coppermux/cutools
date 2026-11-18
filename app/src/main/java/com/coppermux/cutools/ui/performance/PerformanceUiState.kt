package com.coppermux.cutools.ui.performance

import com.coppermux.cutools.core.performance.models.PerformanceCapabilityStatus
import com.coppermux.cutools.core.performance.models.PerformanceMetricsSnapshotModel

/**
 * Immutable UI snapshot for the Performance panel (Phase 11B).
 *
 * Mirrors genuine engine truth only — [status] is derived from the
 * [com.coppermux.cutools.core.performance.PerformanceEngine] capability,
 * [currentMetrics] holds the latest genuine `Performance.metrics` / getMetrics
 * snapshot, [timeline] accumulates the real-time metric history for the line
 * visualization, and [errorMessage] carries an explicit renderable error
 * resolved from an engine failure. No mock or synthetic metric state is ever
 * produced (No-Fake-Capability rule).
 *
 * @property status The explicit `Performance` capability (SUPPORTED /
 *   UNSUPPORTED / UNAVAILABLE / FAILED).
 * @property enabled Whether `Performance` domain notifications are active.
 * @property currentMetrics The latest genuine metric snapshot, or null when none
 *   observed.
 * @property timeline The recent metric snapshot history, oldest first.
 * @property metricFilter A filter over which metric names are highlighted /
 *   visualized in the UI.
 * @property timeDomain The active CDP `Performance` time-domain
 *   ("timeTicks"/"threadTicks") supplied on enable, or null.
 * @property isLoading True while a refreshMetrics command is in flight.
 * @property errorMessage A renderable banner string, or null when no error.
 */
data class PerformanceUiState(
    val status: PerformanceCapabilityStatus = PerformanceCapabilityStatus.UNAVAILABLE,
    val enabled: Boolean = false,
    val currentMetrics: PerformanceMetricsSnapshotModel? = null,
    val timeline: List<PerformanceMetricsSnapshotModel> = emptyList(),
    val metricFilter: String = "",
    val timeDomain: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)