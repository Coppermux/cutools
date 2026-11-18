package com.coppermux.cutools.ui.performance

import com.coppermux.cutools.core.performance.PerformanceEngine
import com.coppermux.cutools.core.performance.models.PerformanceActionResult
import com.coppermux.cutools.core.performance.models.PerformanceCapabilityStatus
import com.coppermux.cutools.core.performance.models.PerformanceError
import com.coppermux.cutools.core.performance.models.PerformanceMetricsSnapshotModel
import com.coppermux.cutools.core.target.SessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for the Phase 11B Performance panel.
 *
 * Binds to the shared [PerformanceEngine] (Phase 10) and exposes a single
 * [state] `StateFlow` consumed by the Compose UI. It issues genuine engine
 * commands — `enablePerformance`, `disablePerformance`, `refreshMetrics`, and
 * time-domain configuration — and propagates explicit engine-resolved outcomes
 * into [PerformanceUiState]: an engine failure (Unsupported / Unavailable /
 * ExecutionFailed / TargetDisconnected) surfaces as an explicit
 * [PerformanceUiState.errorMessage] banner and a matching
 * [PerformanceUiState.status], never as synthetic metric data
 * (No-Fake-Capability rule).
 *
 * Real-time metric streaming is delegated directly to the engine: [metrics]
 * exposes the engine's live `Performance.metrics` [StateFlow], and each genuine
 * snapshot is appended to the [PerformanceUiState.timeline] history for the line
 * visualization. Metric filtering is pure and deterministic.
 *
 * Threading: [state] is a thread-safe [StateFlow]; all engine reads are
 * non-blocking volatile reads, and engine callbacks are delivered on the
 * dispatcher's callback thread.
 */
class PerformanceViewModel(
    private val engine: PerformanceEngine,
) {

    private val _state = MutableStateFlow(PerformanceUiState())
    val state: StateFlow<PerformanceUiState> = _state

    /** The active attached target session, or null when detached. */
    @Volatile
    var attachedSession: SessionInfo? = null
        private set

    val isAttached: Boolean get() = attachedSession != null

    /**
     * The live real-time `Performance.metrics` snapshot, delegated to the engine.
     * The UI collects this [StateFlow] to render metrics as they arrive.
     */
    val metrics: StateFlow<PerformanceMetricsSnapshotModel?> get() = engine.metrics

    /** Whether `Performance` domain notifications are currently enabled. */
    val isEnabled: Boolean get() = engine.enabled

    // ================================================================
    // Target lifecycle
    // ================================================================

    /** Binds the performance engine to a newly-attached target session. */
    fun attachTarget(session: SessionInfo) {
        engine.bindSession(session)
        attachedSession = session
        publish(
            PerformanceUiState(
                status = engine.capability ?: PerformanceCapabilityStatus.UNAVAILABLE,
                enabled = engine.enabled,
                currentMetrics = engine.metrics.value,
                timeDomain = _state.value.timeDomain,
            )
        )
    }

    /** Detaches the performance engine, clearing all target-scoped state. */
    fun detachTarget() {
        engine.detach()
        attachedSession = null
        publish(PerformanceUiState())
    }

    /** Handles connection loss across the performance engine. */
    fun onConnectionLost() {
        engine.onConnectionLost()
        attachedSession = null
        publish(PerformanceUiState())
    }

    /** Releases the performance engine's event subscriptions. */
    fun shutdown() {
        engine.shutdown()
    }

    // ================================================================
    // Domain lifecycle & commands
    // ================================================================

    /**
     * Enables `Performance` domain metrics via the engine, using the current
     * [PerformanceUiState.timeDomain] when supplied.
     */
    fun enablePerformance(callback: (PerformanceActionResult<Unit>) -> Unit = {}) {
        val timeDomain = _state.value.timeDomain
        engine.enable(timeDomain) { result ->
            when (result) {
                is PerformanceActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = PerformanceCapabilityStatus.SUPPORTED,
                            enabled = true,
                            errorMessage = null,
                        )
                    )
                }
                is PerformanceActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: PerformanceCapabilityStatus.FAILED,
                            enabled = false,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /** Disables `Performance` domain metrics via the engine. */
    fun disablePerformance(callback: (PerformanceActionResult<Unit>) -> Unit = {}) {
        engine.disable { result ->
            when (result) {
                is PerformanceActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            status = PerformanceCapabilityStatus.SUPPORTED,
                            enabled = false,
                            errorMessage = null,
                        )
                    )
                }
                is PerformanceActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            status = engine.capability ?: PerformanceCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Requests the current metrics via `Performance.getMetrics` and records the
     * genuine snapshot into [PerformanceUiState.currentMetrics] and the
     * [PerformanceUiState.timeline] history on success.
     */
    fun refreshMetrics(callback: (PerformanceActionResult<PerformanceMetricsSnapshotModel>) -> Unit = {}) {
        publish(_state.value.copy(isLoading = true, errorMessage = null))
        engine.getMetrics { result ->
            when (result) {
                is PerformanceActionResult.Success -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = PerformanceCapabilityStatus.SUPPORTED,
                            currentMetrics = result.data,
                            timeline = appendTimeline(_state.value.timeline, result.data),
                            errorMessage = null,
                        )
                    )
                }
                is PerformanceActionResult.Failure -> {
                    publish(
                        _state.value.copy(
                            isLoading = false,
                            status = engine.capability ?: PerformanceCapabilityStatus.FAILED,
                            errorMessage = describeError(result.error),
                        )
                    )
                }
            }
            callback(result)
        }
    }

    /**
     * Configures the CDP `Performance` time-domain used on the next enable.
     * The change takes effect on the next [enablePerformance] call.
     */
    fun setTimeDomain(timeDomain: String?) {
        publish(_state.value.copy(timeDomain = timeDomain))
    }

    /**
     * Updates the metric filter applied to the rendered metric list / line
     * visualization. Filtering is derived lazily via [filteredMetrics].
     */
    fun updateMetricFilter(filter: String) {
        publish(_state.value.copy(metricFilter = filter))
    }

    /**
     * The metric names matching the active [PerformanceUiState.metricFilter]
     * (case-insensitive substring); an empty filter returns all metric names.
     * Pure and deterministic.
     */
    fun filteredMetricNames(snapshot: PerformanceMetricsSnapshotModel?): List<String> {
        if (snapshot == null) return emptyList()
        val names = snapshot.metrics.map { it.name }
        val trimmed = _state.value.metricFilter.trim()
        if (trimmed.isEmpty()) return names
        val needle = trimmed.lowercase()
        return names.filter { it.lowercase().contains(needle) }
    }

    // ================================================================
    // State & error helpers
    // ================================================================

    /**
     * Appends a genuine snapshot to the bounded timeline history (kept to the
     * most recent [MAX_TIMELINE] entries, oldest first).
     */
    internal fun appendTimeline(
        existing: List<PerformanceMetricsSnapshotModel>,
        snapshot: PerformanceMetricsSnapshotModel,
    ): List<PerformanceMetricsSnapshotModel> {
        val appended = existing + snapshot
        return if (appended.size > MAX_TIMELINE) appended.takeLast(MAX_TIMELINE) else appended
    }

    /**
     * Maps an explicit [PerformanceError] to a renderable banner string
     * (No-Fake-Capability: the banner is derived from the genuine engine
     * failure, never synthesized success).
     */
    fun describeError(error: PerformanceError): String = when (error) {
        is PerformanceError.Unsupported -> "Unsupported: the Performance domain is not available on this target."
        is PerformanceError.Unavailable -> "Unavailable: no attached target session."
        is PerformanceError.TargetDisconnected -> "Failed: target disconnected."
        is PerformanceError.ExecutionFailed -> "Failed: ${error.message.ifEmpty { "execution failed" }}"
    }

    private fun publish(next: PerformanceUiState) {
        _state.value = next
    }

    companion object {
        /** The maximum number of timeline snapshots retained for visualization. */
        const val MAX_TIMELINE: Int = 60
    }
}