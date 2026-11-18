package com.coppermux.cutools.ui.profiling

import com.coppermux.cutools.core.profiling.models.TraceRecord
import com.coppermux.cutools.core.profiling.models.TraceTimeline
import com.coppermux.cutools.core.profiling.models.TracingCategory
import com.coppermux.cutools.core.profiling.models.TracingStatus
import com.coppermux.cutools.tools.profiling.models.PerformanceViewModelSnapshot
import com.coppermux.cutools.tools.profiling.models.ProfilingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Sub-Phase 7D pure UI presentation composables
 * ([PerformancePresentation]) — status text, `UNSUPPORTED` / `UNAVAILABLE` /
 * `FAILED` banner derivation, mode-based row rendering, and formatters. These
 * run without an Android activity because the presentation layer is pure.
 */
class PerformancePresentationTest {

    // ============ status text ============

    @Test
    fun statusText_reportsDetached() {
        val snapshot = PerformanceViewModelSnapshot()
        assertTrue(PerformancePresentation.statusText(snapshot).contains("Not attached"))
    }

    @Test
    fun statusText_reportsActiveModeAndEngineStatus() {
        val snapshot = PerformanceViewModelSnapshot(
            attached = true,
            sessionId = "s-1",
            targetId = "t-1",
            mode = ProfilingMode.CPU_PROFILE,
            cpuStatus = com.coppermux.cutools.core.profiling.models.CpuProfilerStatus.PROFILING,
        )
        val text = PerformancePresentation.statusText(snapshot)
        assertTrue(text.contains("Attached"))
        assertTrue(text.contains("CPU"))
        assertTrue(text.contains("profiling"))
    }

    // ============ banner ============

    @Test
    fun banner_returnsNullWhenNoError() {
        assertEquals(null, PerformancePresentation.banner(PerformanceViewModelSnapshot()))
    }

    @Test
    fun banner_surfacesModeSpecificError() {
        val snapshot = PerformanceViewModelSnapshot(
            mode = ProfilingMode.HEAP_SNAPSHOT,
            heapError = "Unavailable: no session",
        )
        assertEquals("Unavailable: no session", PerformancePresentation.banner(snapshot))
    }

    @Test
    fun banner_ignoresErrorForOtherMode() {
        val snapshot = PerformanceViewModelSnapshot(
            mode = ProfilingMode.HEAP_SNAPSHOT,
            tracingError = "Failed: nope",
        )
        assertNull(PerformancePresentation.banner(snapshot))
    }

    // ============ timeline rows ============

    @Test
    fun rows_timeline_renderClassifiedRecords() {
        val timeline = TraceTimeline(
            status = TracingStatus.STOPPED,
            eventCount = 2,
            longTasks = listOf(TraceRecord("RunTask", TracingCategory.TIMELINE, 100, 60000)),
            scriptExecutions = listOf(TraceRecord("FunctionCall", TracingCategory.V8, 200, 9000)),
            startedAtMicros = 100,
            endedAtMicros = 250_000,
        )
        val snapshot = PerformanceViewModelSnapshot(
            attached = true,
            tracingStatus = TracingStatus.STOPPED,
            timeline = timeline,
        )
        val rows = PerformancePresentation.rows(snapshot)
        assertTrue(rows.any { it.label.startsWith("Long task") })
        assertTrue(rows.any { it.label.startsWith("Script") })
    }

    @Test
    fun rows_timeline_showEmptyStateWhenNothingRecorded() {
        val snapshot = PerformanceViewModelSnapshot(
            attached = true,
            tracingStatus = TracingStatus.STOPPED,
            timeline = TraceTimeline(status = TracingStatus.STOPPED),
        )
        val rows = PerformancePresentation.rows(snapshot)
        assertEquals(1, rows.size)
        assertTrue(rows[0].label.contains("No recorded trace activity"))
    }

    // ============ heap rows ============

    @Test
    fun rows_heap_renderSnapshotSummaryAndSamplingNodes() {
        val snapshot = PerformanceViewModelSnapshot(
            attached = true,
            mode = ProfilingMode.HEAP_SNAPSHOT,
            heapStatus = com.coppermux.cutools.core.profiling.models.HeapProfilerStatus.STOPPED,
            heapSnapshot = com.coppermux.cutools.core.profiling.models.HeapSnapshotResult(
                snapshotJson = """{"nodes":[]}""",
                chunkCount = 3,
                byteSize = 12,
            ),
            heapProfile = com.coppermux.cutools.core.profiling.models.SamplingHeapProfileModel(
                head = com.coppermux.cutools.core.profiling.models.SamplingHeapProfileNode(
                    selfSize = 1024.0,
                    totalSize = 2048.0,
                    functionName = "root",
                    children = listOf(
                        com.coppermux.cutools.core.profiling.models.SamplingHeapProfileNode(
                            selfSize = 512.0,
                            totalSize = 512.0,
                            functionName = "child",
                        ),
                    ),
                ),
            ),
        )
        val rows = PerformancePresentation.rows(snapshot)
        assertTrue(rows.any { it.label == "Heap snapshot" && it.detail.contains("12 B") })
        assertTrue(rows.any { it.label.contains("root") })
        assertTrue(rows.any { it.label.contains("child") })
    }

    // ============ formatters ============

    @Test
    fun formatBytes_scalesUnits() {
        assertEquals("512 B", PerformancePresentation.formatBytes(512.0))
        assertEquals("1 KB", PerformancePresentation.formatBytes(1024.0))
        assertEquals("1 MB", PerformancePresentation.formatBytes(1_048_576.0))
    }

    @Test
    fun formatMillis_scalesToMilliseconds() {
        assertEquals("0.5 ms", PerformancePresentation.formatUs(500.0))
        assertEquals("1 ms", PerformancePresentation.formatMillis(1.0))
    }
}