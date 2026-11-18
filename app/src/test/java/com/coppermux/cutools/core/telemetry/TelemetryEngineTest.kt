package com.coppermux.cutools.core.telemetry

import com.coppermux.cutools.core.capability.CapabilityStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 15 — Telemetry & Capability Auditor unit tests.
 *
 * Validates metric aggregation (command latencies, event throughput, reconnect
 * stats, capability fallbacks), thread safety under concurrent recorders, state
 * flow emissions, bounded event-log retention, and the opt-in/opt-out toggle.
 * All metrics are asserted from genuine recorded values — nothing is fabricated.
 */
class TelemetryEngineTest {

    private fun enabledEngine(): TelemetryEngine = TelemetryEngine(initialEnabled = true)

    // ================================================================
    // Command latency aggregation
    // ================================================================

    @Test
    fun commandLatency_aggregatesCountTotalMinMaxAndMean() {
        val engine = enabledEngine()
        engine.recordCommandLatency("Runtime.evaluate", 100)
        engine.recordCommandLatency("Runtime.evaluate", 300)
        engine.recordCommandLatency("Page.navigate", 200)

        val metrics = engine.snapshot().commandLatency.getValue("Runtime.evaluate")
        assertEquals(2L, metrics.count)
        assertEquals(400L, metrics.totalNanos)
        assertEquals(100L, metrics.minNanos)
        assertEquals(300L, metrics.maxNanos)
        assertEquals(200.0, metrics.meanNanos, 0.001)

        assertEquals(200L, engine.snapshot().commandLatency.getValue("Page.navigate").totalNanos)
    }

    @Test
    fun commandLatency_ignoresNonPositiveDurations() {
        val engine = enabledEngine()
        engine.recordCommandLatency("Runtime.evaluate", 0)
        engine.recordCommandLatency("Runtime.evaluate", -5)
        assertTrue(engine.snapshot().commandLatency.isEmpty())
    }

    @Test
    fun commandLatency_tracksDistinctMethodsIndependently() {
        val engine = enabledEngine()
        engine.recordCommandLatency("A", 111)
        engine.recordCommandLatency("B", 222)
        assertEquals(1L, engine.snapshot().commandLatency["A"]!!.count)
        assertEquals(1L, engine.snapshot().commandLatency["B"]!!.count)
    }

    // ================================================================
    // Event streaming throughput
    // ================================================================

    @Test
    fun eventThroughput_countsTotalWindowAndPerMethod() {
        val engine = enabledEngine()
        engine.recordEventReceived("Network.requestWillBeSent")
        engine.recordEventReceived("Network.requestWillBeSent")
        engine.recordEventReceived("Network.responseReceived")

        val throughput = engine.snapshot().eventThroughput
        assertEquals(3L, throughput.totalEvents)
        assertEquals(2L, throughput.eventCountsByMethod.getValue("Network.requestWillBeSent"))
        assertEquals(1L, throughput.eventCountsByMethod.getValue("Network.responseReceived"))
    }

    @Test
    fun eventWindow_rotatesAndCapturesBurstCount() {
        val engine = enabledEngine()
        engine.recordEventReceived("A")
        engine.recordEventReceived("A")
        engine.recordEventReceived("A")
        engine.rotateEventWindow()
        engine.recordEventReceived("A")
        engine.recordEventReceived("A")
        engine.rotateEventWindow()

        val throughput = engine.snapshot().eventThroughput
        assertEquals(5L, throughput.totalEvents)
        assertEquals(2L, throughput.lastWindowCount)
    }

    // ================================================================
    // Transport reconnect
    // ================================================================

    @Test
    fun reconnectStats_accumulateAttemptsSuccessesAndExhaustion() {
        val engine = enabledEngine()
        engine.recordReconnectAttempt()
        engine.recordReconnectAttempt()
        engine.recordReconnectSuccess(backoffDelayMs = 500)
        engine.recordReconnectExhausted()

        val reconnect = engine.snapshot().reconnect
        assertEquals(2L, reconnect.reconnectAttempts)
        assertEquals(1L, reconnect.successfulReconnects)
        assertEquals(1L, reconnect.exhaustedCount)
        assertEquals(500L, reconnect.lastBackoffDelayMs)
        assertTrue(reconnect.lastReconnectAtEpochMillis > 0L)
    }

    // ================================================================
    // Capability fallback audit
    // ================================================================

    @Test
    fun capabilityFallback_bucketsUnsupportedUnavailableAndFailed() {
        val engine = enabledEngine()
        engine.recordCapabilityFallback("MemoryEngine", "HeapProfiler.startSampling", CapabilityStatus.Unsupported)
        engine.recordCapabilityFallback("MemoryEngine", "HeapProfiler.enable", CapabilityStatus.Unsupported)
        engine.recordCapabilityFallback("TargetEngine", "Target.attachToTarget", CapabilityStatus.Unavailable)
        engine.recordCapabilityFallback("NetworkEngine", "Network.enable", CapabilityStatus.Failed("boom"))

        val stats = engine.fallbackAudit()
        assertEquals(3, stats.size)

        val memory = stats.first { it.engine == "MemoryEngine" }
        assertEquals(2L, memory.unsupportedCount)
        assertEquals(0L, memory.unavailableCount)
        assertEquals(2L, memory.total)

        val target = stats.first { it.engine == "TargetEngine" }
        assertEquals(1L, target.unavailableCount)
        assertEquals(1L, target.total)

        val network = stats.first { it.engine == "NetworkEngine" }
        assertEquals(1L, network.failedCount)
    }

    @Test
    fun capabilityFallback_ignoresSupportedAndExperimental() {
        val engine = enabledEngine()
        engine.recordCapabilityFallback("MemoryEngine", "Memory.getDOMCounters", CapabilityStatus.Supported)
        engine.recordCapabilityFallback("MemoryEngine", "HeapProfiler.enable", CapabilityStatus.Experimental)
        assertTrue(engine.fallbackAudit().isEmpty())
    }

    @Test
    fun fallbackEventLog_recordsEveryAcceptedFallbackWithStatus() {
        val engine = enabledEngine()
        engine.recordCapabilityFallback("MemoryEngine", "HeapProfiler.startSampling", CapabilityStatus.Unsupported)
        val records = engine.snapshot().fallbackEventLog
        assertEquals(1, records.size)
        assertEquals("MemoryEngine", records[0].engine)
        assertEquals("HeapProfiler.startSampling", records[0].method)
        assertTrue(records[0].status is CapabilityStatus.Unsupported)
    }

    @Test
    fun fallbackEventLog_isBounded() {
        val engine = TelemetryEngine(initialEnabled = true, maxFallbackLogSize = 3)
        repeat(10) {
            engine.recordCapabilityFallback("MemoryEngine", "m$it", CapabilityStatus.Unavailable)
        }
        assertEquals(3, engine.snapshot().fallbackEventLog.size)
        // Oldest records are evicted first.
        assertEquals("m7", engine.snapshot().fallbackEventLog.first().method)
        assertEquals("m9", engine.snapshot().fallbackEventLog.last().method)
    }

    // ================================================================
    // Heartbeat
    // ================================================================

    @Test
    fun heartbeat_incrementsMonotonically() {
        val engine = enabledEngine()
        engine.recordHeartbeat()
        engine.recordHeartbeat()
        engine.recordHeartbeat()
        assertEquals(3L, engine.snapshot().heartbeatCount)
    }

    // ================================================================
    // Opt-in / opt-out
    // ================================================================

    @Test
    fun disabledByDefault_allRecordsAreNoOps() {
        val engine = TelemetryEngine(initialEnabled = false)
        engine.recordCommandLatency("Runtime.evaluate", 100)
        engine.recordEventReceived("Network.requestWillBeSent")
        engine.recordCapabilityFallback("MemoryEngine", "m", CapabilityStatus.Unsupported)
        engine.recordHeartbeat()

        val snapshot = engine.snapshot()
        assertFalse(snapshot.enabled)
        assertTrue(snapshot.commandLatency.isEmpty())
        assertEquals(0L, snapshot.eventThroughput.totalEvents)
        assertTrue(snapshot.fallbackEventLog.isEmpty())
        assertEquals(0L, snapshot.heartbeatCount)
    }

    @Test
    fun togglingOptOut_stopsCollectionAndMemoriesAreRetained() {
        val engine = enabledEngine()
        engine.recordCommandLatency("Runtime.evaluate", 100)
        val before = engine.snapshot().commandLatency.getValue("Runtime.evaluate").count

        engine.setEnabled(false)
        engine.recordCommandLatency("Runtime.evaluate", 200)
        val disabled = engine.snapshot()
        assertFalse(disabled.enabled)
        // Records made while disabled do not accumulate.
        assertEquals(before, disabled.commandLatency.getValue("Runtime.evaluate").count)

        engine.setEnabled(true)
        engine.recordCommandLatency("Runtime.evaluate", 200)
        assertEquals(before + 1L, engine.snapshot().commandLatency.getValue("Runtime.evaluate").count)
    }

    // ================================================================
    // Reset
    // ================================================================

    @Test
    fun reset_clearsAllMetricsAndPreservesEnabledToggle() {
        val engine = TelemetryEngine(initialEnabled = true)
        engine.recordCommandLatency("Runtime.evaluate", 100)
        engine.recordEventReceived("N")
        engine.recordCapabilityFallback("MemoryEngine", "m", CapabilityStatus.Unsupported)
        engine.recordReconnectAttempt()
        engine.recordHeartbeat()

        engine.reset()
        val snapshot = engine.snapshot()
        assertTrue(snapshot.enabled)
        assertTrue(snapshot.commandLatency.isEmpty())
        assertEquals(0L, snapshot.eventThroughput.totalEvents)
        assertTrue(snapshot.fallbackEventLog.isEmpty())
        assertTrue(snapshot.capabilityFallbacks.isEmpty())
        assertEquals(0L, snapshot.reconnect.reconnectAttempts)
        assertEquals(0L, snapshot.heartbeatCount)
    }

    // ================================================================
    // State flow emissions
    // ================================================================

    @Test
    fun stateFlow_emitsUpdatedSnapshots() = runBlocking {
        val engine = enabledEngine()
        val first = engine.state.first { it.enabled }
        assertTrue(first.enabled)

        engine.recordCommandLatency("Runtime.evaluate", 250)
        val updated = engine.state.first { it.commandLatency.containsKey("Runtime.evaluate") }
        assertEquals(250L, updated.commandLatency.getValue("Runtime.evaluate").totalNanos)

        engine.recordCapabilityFallback("MemoryEngine", "m", CapabilityStatus.Unsupported)
        val withFallback = engine.state.first { it.capabilityFallbacks.isNotEmpty() }
        assertEquals(1L, withFallback.capabilityFallbacks.getValue("MemoryEngine").unsupportedCount)
    }

    @Test
    fun stateFlow_optOutEmitsDisabledSnapshot() = runBlocking {
        val engine = TelemetryEngine(initialEnabled = true)
        engine.setEnabled(false)
        val disabled = engine.state.first { !it.enabled }
        assertFalse(disabled.enabled)
    }

    // ================================================================
    // Thread safety
    // ================================================================

    @Test
    fun concurrentRecorders_neverTearAggregation() {
        val engine = enabledEngine()
        val threads = 8
        val recordsPerThread = 2_000
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val failures = AtomicInteger(0)

        repeat(threads) { threadIndex ->
            pool.execute {
                try {
                    repeat(recordsPerThread) { i ->
                        engine.recordCommandLatency("Engine.method$i", 1L or i.toLong())
                        engine.recordEventReceived(if (i % 2 == 0) "E.even" else "E.odd")
                        if (i % 50 == 0) {
                            engine.recordCapabilityFallback(
                                "Engine$threadIndex",
                                "m$i",
                                if (i % 3 == 0) CapabilityStatus.Unsupported else CapabilityStatus.Unavailable,
                            )
                        }
                        if (i % 200 == 0) engine.rotateEventWindow()
                    }
                } catch (t: Throwable) {
                    failures.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("recorders must finish", latch.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(0, failures.get())

        val snapshot = engine.snapshot()
        // Every per-thread per-index latency was recorded exactly once across threads.
        assertEquals(threads.toLong() * recordsPerThread, snapshot.commandLatency.values.sumOf { it.count })
        // Total events is exactly the sum across all threads.
        assertEquals(threads.toLong() * recordsPerThread, snapshot.eventThroughput.totalEvents)
        // Every engine bucket exists.
        repeat(threads) { i ->
            val stats = snapshot.capabilityFallbacks["Engine$i"]
            assertTrue("Engine$i must be present", stats != null)
        }
    }
}