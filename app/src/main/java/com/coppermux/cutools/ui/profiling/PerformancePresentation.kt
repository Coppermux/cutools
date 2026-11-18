package com.coppermux.cutools.ui.profiling

import com.coppermux.cutools.core.profiling.models.CpuProfilerStatus
import com.coppermux.cutools.core.profiling.models.HeapProfilerStatus
import com.coppermux.cutools.core.profiling.models.SamplingHeapProfileNode
import com.coppermux.cutools.core.profiling.models.TraceRecord
import com.coppermux.cutools.core.profiling.models.TracingCategory
import com.coppermux.cutools.core.profiling.models.TracingStatus
import com.coppermux.cutools.tools.profiling.models.PerformanceViewModelSnapshot
import com.coppermux.cutools.tools.profiling.models.ProfilingMode

/**
 * A single renderable row in the Performance tool's active-tab list.
 *
 * Rows are pure presentation — derived deterministically from the
 * [PerformanceViewModelSnapshot] by [PerformancePresentation], never from raw
 * CDP. [label] is the primary text, [detail] the secondary value, and [caption]
 * an optional trailing annotation.
 */
data class PerformanceRow(
    val label: String,
    val detail: String = "",
    val caption: String = "",
)

/**
 * Pure, testable presentation layer for the Performance tool.
 *
 * Maps a [PerformanceViewModelSnapshot] to the renderable surface: a status
 * summary line, a `UNSUPPORTED` / `UNAVAILABLE` / `FAILED` banner (derived
 * solely from engine-resolved errors), and the content rows for the active
 * [ProfilingMode] tab. All formatting is deterministic so the UI renders the
 * exact same text as any test would assert — no browser truth is owned here.
 */
object PerformancePresentation {

    /** A single-line status summary for the tool header. */
    fun statusText(snapshot: PerformanceViewModelSnapshot): String = buildString {
        if (!snapshot.attached) {
            append("Not attached to a target")
            return@buildString
        }
        append("Attached")
        when (snapshot.mode) {
            ProfilingMode.TIMELINE -> append(" · Tracing ").append(tracingLabel(snapshot.tracingStatus))
            ProfilingMode.CPU_PROFILE -> append(" · CPU ").append(cpuLabel(snapshot.cpuStatus))
            ProfilingMode.HEAP_SNAPSHOT -> append(" · Heap ").append(heapLabel(snapshot.heapStatus))
        }
    }

    /**
     * The banner text to show, or null when no engine error is present.
     *
     * The error strings resolved by the ViewModel already carry the
     * `Unsupported:` / `Unavailable:` / `Failed:` status prefix, so this is a
     * pure pass-through of genuine protocol state — never fabricated.
     */
    fun banner(snapshot: PerformanceViewModelSnapshot): String? = when (snapshot.mode) {
        ProfilingMode.TIMELINE -> snapshot.tracingError
        ProfilingMode.CPU_PROFILE -> snapshot.cpuError
        ProfilingMode.HEAP_SNAPSHOT -> snapshot.heapError
    }

    /** The content rows for the active [ProfilingMode] tab. */
    fun rows(snapshot: PerformanceViewModelSnapshot): List<PerformanceRow> = when (snapshot.mode) {
        ProfilingMode.TIMELINE -> timelineRows(snapshot)
        ProfilingMode.CPU_PROFILE -> cpuRows(snapshot)
        ProfilingMode.HEAP_SNAPSHOT -> heapRows(snapshot)
    }

    // ================================================================
    // Timeline
    // ================================================================

    private fun timelineRows(snapshot: PerformanceViewModelSnapshot): List<PerformanceRow> {
        if (snapshot.tracingStatus != TracingStatus.STOPPED) {
            return listOf(
                PerformanceRow(label = tracingLabel(snapshot.tracingStatus), detail = traceSummary(snapshot)),
            )
        }
        val out = ArrayList<PerformanceRow>()
        val tl = snapshot.timeline
        if (tl.longTasks.isNotEmpty()) {
            tl.longTasks.forEach { out.add(recordRow("Long task", it)) }
        }
        tl.layoutBlocks.forEach { out.add(recordRow("Layout", it)) }
        tl.paintBlocks.forEach { out.add(recordRow("Paint", it)) }
        tl.scriptExecutions.forEach { out.add(recordRow("Script", it)) }
        tl.renderingBlocks.forEach { out.add(recordRow("Render", it)) }
        tl.userTimingMarks.forEach { mark ->
            out.add(PerformanceRow(label = "Mark", detail = mark.name, caption = "${mark.tsMicros}μs"))
        }
        if (out.isEmpty()) {
            out += PerformanceRow(label = "No recorded trace activity", detail = traceSummary(snapshot))
        }
        return out
    }

    private fun recordRow(kind: String, record: TraceRecord): PerformanceRow = PerformanceRow(
        label = "$kind · ${record.name}",
        detail = formatMillis(record.durationMicros / 1000.0),
        caption = "${record.category.wireValue} @ ${record.startMicros}μs",
    )

    private fun traceSummary(snapshot: PerformanceViewModelSnapshot): String =
        "${snapshot.timeline.eventCount} events · ${windowMillis(snapshot)}"

    // ================================================================
    // CPU call tree
    // ================================================================

    private fun cpuRows(snapshot: PerformanceViewModelSnapshot): List<PerformanceRow> {
        val profile = snapshot.cpuProfile ?: return listOf(PerformanceRow(label = cpuLabel(snapshot.cpuStatus)))
        if (profile.timings.isEmpty()) {
            return listOf(PerformanceRow(label = "No sampled functions", detail = "${profile.duration.toLong()} μs window"))
        }
        return profile.timings.map { timing ->
            val node = profile.profile.nodes.firstOrNull { it.id == timing.nodeId }
            PerformanceRow(
                label = node?.functionName ?: "function #${timing.nodeId}",
                detail = "${formatUs(timing.selfTime)} self · ${formatUs(timing.totalTime)} total",
                caption = node?.url ?: "",
            )
        }
    }

    // ================================================================
    // Heap snapshot / sampling
    // ================================================================

    private fun heapRows(snapshot: PerformanceViewModelSnapshot): List<PerformanceRow> {
        val snapshotResult = snapshot.heapSnapshot
        val sampling = snapshot.heapProfile
        val out = ArrayList<PerformanceRow>()
        if (snapshotResult != null && snapshotResult.snapshotJson.isNotEmpty()) {
            out += PerformanceRow(
                label = "Heap snapshot",
                detail = formatBytes(snapshotResult.byteSize.toDouble()),
                caption = "${snapshotResult.chunkCount} chunks",
            )
        }
        if (sampling?.head != null) {
            appendNodeRows(sampling.head, 0, out)
        }
        if (out.isEmpty()) {
            out += PerformanceRow(label = heapLabel(snapshot.heapStatus))
        }
        return out
    }

    private fun appendNodeRows(node: SamplingHeapProfileNode, depth: Int, out: MutableList<PerformanceRow>) {
        out += PerformanceRow(
            label = "  ".repeat(depth) + (node.functionName ?: "anonymous"),
            detail = "${formatBytes(node.selfSize)} self · ${formatBytes(node.totalSize)} total",
        )
        node.children.forEach { appendNodeRows(it, depth + 1, out) }
    }

    // ================================================================
    // Labels & formatters
    // ================================================================

    private fun tracingLabel(status: TracingStatus): String = when (status) {
        TracingStatus.IDLE -> "idle"
        TracingStatus.RECORDING -> "recording…"
        TracingStatus.PROCESSING -> "processing…"
        TracingStatus.STOPPED -> "stopped"
    }

    private fun cpuLabel(status: CpuProfilerStatus): String = when (status) {
        CpuProfilerStatus.IDLE -> "idle"
        CpuProfilerStatus.PROFILING -> "profiling…"
        CpuProfilerStatus.PROCESSING -> "processing…"
        CpuProfilerStatus.STOPPED -> "stopped"
    }

    private fun heapLabel(status: HeapProfilerStatus): String = when (status) {
        HeapProfilerStatus.IDLE -> "idle"
        HeapProfilerStatus.TAKING_SNAPSHOT -> "taking snapshot…"
        HeapProfilerStatus.SAMPLING -> "sampling…"
        HeapProfilerStatus.STOPPED -> "stopped"
    }

    /** Formats a duration in microseconds as a millisecond string. */
    fun formatUs(us: Double): String = formatMillis(us / 1000.0)

    /** Formats a duration in milliseconds as a `x.y ms` string. */
    fun formatMillis(ms: Double): String = "${trim(Math.round(ms * 10.0) / 10.0)} ms"

    /** Formats a byte count as a human-readable string. */
    fun formatBytes(bytes: Double): String = when {
        bytes >= 1_048_576.0 -> "${trim(Math.round(bytes / 1_048_576.0 * 10.0) / 10.0)} MB"
        bytes >= 1024.0 -> "${trim(Math.round(bytes / 1024.0 * 10.0) / 10.0)} KB"
        else -> "${Math.round(bytes)} B"
    }

    /** Renders [value] as a plain integer when whole, e.g. `1.0` → `1`. */
    private fun trim(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    /** Convenience wrapper over [formatMillis] for the timeline window. */
    fun windowMillis(snapshot: PerformanceViewModelSnapshot): String =
        formatMillis((snapshot.timeline.endedAtMicros - snapshot.timeline.startedAtMicros) / 1000.0)
}