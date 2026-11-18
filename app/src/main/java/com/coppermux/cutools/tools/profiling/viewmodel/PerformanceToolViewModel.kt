package com.coppermux.cutools.tools.profiling.viewmodel

import com.coppermux.cutools.core.profiling.CpuProfilerEngine
import com.coppermux.cutools.core.profiling.HeapProfilerEngine
import com.coppermux.cutools.core.profiling.TracingEngine
import com.coppermux.cutools.core.profiling.models.CpuProfileTiming
import com.coppermux.cutools.core.profiling.models.CpuProfilerActionResult
import com.coppermux.cutools.core.profiling.models.CpuProfilerError
import com.coppermux.cutools.core.profiling.models.HeapProfilerActionResult
import com.coppermux.cutools.core.profiling.models.HeapProfilerError
import com.coppermux.cutools.core.profiling.models.HeapSnapshotResult
import com.coppermux.cutools.core.profiling.models.SamplingHeapProfileModel
import com.coppermux.cutools.core.profiling.models.TraceTimeline
import com.coppermux.cutools.core.profiling.models.TracingActionResult
import com.coppermux.cutools.core.profiling.models.TracingError
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.tools.profiling.models.PerformanceViewModelSnapshot
import com.coppermux.cutools.tools.profiling.models.ProfilingMode

/**
 * Unified DevTools Performance panel ViewModel.
 *
 * Orchestrates the three profiling engines — [TracingEngine], [CpuProfilerEngine],
 * and [HeapProfilerEngine] — into a single thread-safe [state] snapshot. Target
 * attach / detach / connection-loss actions are fanned out to every engine in
 * unison so their session-bound state clears and refreshes together, and action
 * dispatchers start / stop trace recordings, CPU profiles, and heap snapshots
 * while propagating any resolved error back into the snapshot as a renderable
 * `...Error` string.
 *
 * Threading: the aggregated [state] is `@Volatile` and rebuilt non-blocking by
 * reading each engine's own atomic/volatile state, so concurrent event streams
 * across engines never produce a torn snapshot. No Chrome communication is
 * performed here — all commands are delegated to the shared engines.
 */
class PerformanceToolViewModel(
    private val tracing: TracingEngine,
    private val cpu: CpuProfilerEngine,
    private val heap: HeapProfilerEngine,
) {

    /** The most recent aggregated snapshot of all profiling subsystems. */
    @Volatile
    var state: PerformanceViewModelSnapshot = PerformanceViewModelSnapshot.empty()
        private set

    /** The active attached target session, or null when detached. */
    @Volatile
    var attachedSession: SessionInfo? = null
        private set

    val isAttached: Boolean get() = attachedSession != null

    /** The active [ProfilingMode] tab. */
    val mode: ProfilingMode
        get() = state.mode

    /** Renderable error strings resolved by the last dispatched action, if any. */
    @Volatile
    private var tracingError: String? = null
    @Volatile
    private var cpuError: String? = null
    @Volatile
    private var heapError: String? = null

    // ================================================================
    // Target lifecycle
    // ================================================================

    /** Attaches every profiling engine to a newly-attached target session. */
    fun attachTarget(session: SessionInfo) {
        tracing.bindSession(session)
        cpu.bindSession(session)
        heap.bindSession(session)
        this.attachedSession = session
        refresh()
    }

    /** Detaches every profiling engine, clearing all target-scoped state. */
    fun detachTarget() {
        tracing.detach()
        cpu.detach()
        heap.detach()
        attachedSession = null
        refresh()
    }

    /** Handles connection loss across all profiling engines. */
    fun onConnectionLost() {
        tracing.onConnectionLost()
        cpu.onConnectionLost()
        heap.onConnectionLost()
        attachedSession = null
        refresh()
    }

    /** Releases event subscriptions across all event-driven engines. */
    fun shutdown() {
        tracing.shutdown()
        cpu.shutdown()
        heap.shutdown()
    }

    // ================================================================
    // Presentation mode
    // ================================================================

    /** Selects the active [ProfilingMode] tab for the UI. */
    fun selectMode(mode: ProfilingMode) {
        state = state.copy(mode = mode)
    }

    // ================================================================
    // Aggregation
    // ================================================================

    /**
     * Rebuilds the aggregated [state] snapshot from each engine's current state.
     * Each read is non-blocking (each engine field is atomic/volatile).
     */
    fun refresh() {
        val active = attachedSession
        state = PerformanceViewModelSnapshot(
            sessionId = active?.sessionId,
            targetId = active?.targetId,
            attached = active != null,
            mode = state.mode,
            tracingStatus = tracing.status,
            timeline = tracing.timeline,
            tracingError = tracingError,
            cpuStatus = cpu.status,
            cpuProfile = cpu.profile,
            cpuError = cpuError,
            heapStatus = heap.status,
            heapSnapshot = heap.snapshot,
            heapProfile = heap.samplingProfile,
            heapError = heapError,
        )
    }

    // ================================================================
    // Action dispatchers — Tracing
    // ================================================================

    /** Starts a streaming trace recording on the attached target. */
    fun startTracing(callback: (TracingActionResult<Unit>) -> Unit) {
        tracing.startTracing { result ->
            tracingError = if (result is TracingActionResult.Failure) {
                describeTracingError((result as TracingActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Stops the active trace recording and surfaces the classified timeline. */
    fun stopTracing(callback: (TracingActionResult<TraceTimeline>) -> Unit) {
        tracing.endTracing { result ->
            tracingError = if (result is TracingActionResult.Failure) {
                describeTracingError((result as TracingActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    // ================================================================
    // Action dispatchers — CPU Profiler
    // ================================================================

    /** Enables the `Profiler` domain. */
    fun enableCpuProfiler(callback: (CpuProfilerActionResult<Unit>) -> Unit) {
        cpu.enable { result ->
            cpuError = if (result is CpuProfilerActionResult.Failure) {
                describeCpuError((result as CpuProfilerActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Disables the `Profiler` domain. */
    fun disableCpuProfiler(callback: (CpuProfilerActionResult<Unit>) -> Unit) {
        cpu.disable { result ->
            cpuError = if (result is CpuProfilerActionResult.Failure) {
                describeCpuError((result as CpuProfilerActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Starts a JavaScript CPU sampling session. */
    fun startCpuProfile(callback: (CpuProfilerActionResult<Unit>) -> Unit) {
        cpu.startProfiling { result ->
            cpuError = if (result is CpuProfilerActionResult.Failure) {
                describeCpuError((result as CpuProfilerActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Stops the active CPU sampling session and surfaces the call-tree timing. */
    fun stopCpuProfile(callback: (CpuProfilerActionResult<CpuProfileTiming>) -> Unit) {
        cpu.stopProfiling { result ->
            cpuError = if (result is CpuProfilerActionResult.Failure) {
                describeCpuError((result as CpuProfilerActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    // ================================================================
    // Action dispatchers — HeapProfiler
    // ================================================================

    /** Enables the `HeapProfiler` domain. */
    fun enableHeapProfiler(callback: (HeapProfilerActionResult<Unit>) -> Unit) {
        heap.enable { result ->
            heapError = if (result is HeapProfilerActionResult.Failure) {
                describeHeapError((result as HeapProfilerActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Disables the `HeapProfiler` domain. */
    fun disableHeapProfiler(callback: (HeapProfilerActionResult<Unit>) -> Unit) {
        heap.disable { result ->
            heapError = if (result is HeapProfilerActionResult.Failure) {
                describeHeapError((result as HeapProfilerActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Takes a full heap snapshot on the attached target. */
    fun takeHeapSnapshot(
        reportProgress: Boolean = true,
        callback: (HeapProfilerActionResult<HeapSnapshotResult>) -> Unit,
    ) {
        heap.takeHeapSnapshot(reportProgress) { result ->
            heapError = if (result is HeapProfilerActionResult.Failure) {
                describeHeapError((result as HeapProfilerActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Starts heap sampling on the attached target. */
    fun startHeapSampling(
        samplingIntervalBytes: Int? = null,
        callback: (HeapProfilerActionResult<Unit>) -> Unit,
    ) {
        heap.startSampling(samplingIntervalBytes) { result ->
            heapError = if (result is HeapProfilerActionResult.Failure) {
                describeHeapError((result as HeapProfilerActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Stops the active heap sampling and surfaces the sampled allocation tree. */
    fun stopHeapSampling(callback: (HeapProfilerActionResult<SamplingHeapProfileModel>) -> Unit) {
        heap.stopSampling { result ->
            heapError = if (result is HeapProfilerActionResult.Failure) {
                describeHeapError((result as HeapProfilerActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    // ================================================================
    // Error description helpers
    // ================================================================

    private fun describeTracingError(error: TracingError): String = when (error) {
        is TracingError.Unsupported -> "Unsupported: ${error.reason}"
        is TracingError.Unavailable -> "Unavailable: ${error.detail}"
        is TracingError.ExecutionFailed -> "Failed: ${error.cause.message ?: "execution failed"}"
        is TracingError.RecordingInProgress -> "Failed: a recording is already in progress"
    }

    private fun describeCpuError(error: CpuProfilerError): String = when (error) {
        is CpuProfilerError.Unsupported -> "Unsupported: ${error.reason}"
        is CpuProfilerError.Unavailable -> "Unavailable: ${error.detail}"
        is CpuProfilerError.ExecutionFailed -> "Failed: ${error.cause.message ?: "execution failed"}"
        is CpuProfilerError.ProfilingInProgress -> "Failed: a CPU profiling session is already active"
    }

    private fun describeHeapError(error: HeapProfilerError): String = when (error) {
        is HeapProfilerError.Unsupported -> "Unsupported: ${error.reason}"
        is HeapProfilerError.Unavailable -> "Unavailable: ${error.detail}"
        is HeapProfilerError.Failed -> "Failed: ${error.cause.message ?: "execution failed"}"
        is HeapProfilerError.SnapshotInProgress -> "Failed: a heap snapshot is already in progress"
        is HeapProfilerError.SamplingInProgress -> "Failed: heap sampling is already active"
    }
}