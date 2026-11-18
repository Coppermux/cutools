package com.coppermux.cutools.tools.profiling.models

import com.coppermux.cutools.core.profiling.models.CpuProfileTiming
import com.coppermux.cutools.core.profiling.models.CpuProfilerStatus
import com.coppermux.cutools.core.profiling.models.HeapProfilerStatus
import com.coppermux.cutools.core.profiling.models.HeapSnapshotResult
import com.coppermux.cutools.core.profiling.models.SamplingHeapProfileModel
import com.coppermux.cutools.core.profiling.models.TraceTimeline
import com.coppermux.cutools.core.profiling.models.TracingStatus

/**
 * The active tab of the Performance tool.
 *
 * Each value selects the profiling sub-view the [com.coppermux.cutools.tools.profiling.viewmodel.PerformanceToolViewModel]
 * presents: the streamed trace [TraceTimeline], the JavaScript CPU call-tree, or
 * the heap snapshot / sampling summary. It is presentation state only — the
 * underlying profiling engines run independently of which tab is selected.
 */
enum class ProfilingMode {
    /** Trace timeline (long tasks, layout/paint, frame rates). */
    TIMELINE,

    /** JavaScript CPU call-tree / flamegraph. */
    CPU_PROFILE,

    /** Heap snapshot instance counts & memory usage summaries. */
    HEAP_SNAPSHOT,
}

/**
 * Immutable UI snapshot aggregating the active state of every profiling engine
 * ([TracingEngine], [com.coppermux.cutools.core.profiling.CpuProfilerEngine],
 * [com.coppermux.cutools.core.profiling.HeapProfilerEngine]) for the currently
 * attached target.
 *
 * Each engine's genuine state is surfaced as-is (never fabricated — the
 * No-Fake-Capability rule). [sessionId] / [targetId] / [attached] describe the
 * active target; [mode] selects the active tab. When an engine resolves a
 * protocol failure, the corresponding `...Error` field carries a human-readable
 * string so the UI can surface `UNSUPPORTED` / `UNAVAILABLE` / `FAILED` banners
 * without interpreting raw protocol state.
 */
data class PerformanceViewModelSnapshot(
    val sessionId: String? = null,
    val targetId: String? = null,
    val attached: Boolean = false,
    val mode: ProfilingMode = ProfilingMode.TIMELINE,

    // Tracing engine
    val tracingStatus: TracingStatus = TracingStatus.IDLE,
    val timeline: TraceTimeline = TraceTimeline(),
    val tracingError: String? = null,

    // CPU Profiler engine
    val cpuStatus: CpuProfilerStatus = CpuProfilerStatus.IDLE,
    val cpuProfile: CpuProfileTiming? = null,
    val cpuError: String? = null,

    // HeapProfiler engine
    val heapStatus: HeapProfilerStatus = HeapProfilerStatus.IDLE,
    val heapSnapshot: HeapSnapshotResult? = null,
    val heapProfile: SamplingHeapProfileModel? = null,
    val heapError: String? = null,
) {
    companion object {
        fun empty(): PerformanceViewModelSnapshot = PerformanceViewModelSnapshot()
    }
}