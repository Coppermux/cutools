package com.coppermux.cutools.core.transport

import com.coppermux.cutools.core.error.TransportException
import com.coppermux.cutools.core.telemetry.TelemetryEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 15 — Transport Auto-Reconnect & Lifecycle Resilience tests.
 *
 * Verifies exponential-backoff delay calculation, retry caps / exhaustion, clean
 * session recovery under simulated socket drops, active-channel heartbeat
 * monitoring, domain resubscription on re-establishment, and zero leaked
 * reconnect/heartbeat jobs after lifecycle teardown.
 *
 * Reconnections run on a real scheduler with millisecond-scale backoffs
 * (jitter disabled) and are awaited deterministically.
 */
class CdpTransportReconnectTest {

    // ================================================================
    // Exponential backoff calculation
    // ================================================================

    @Test
    fun backoff_pureDelayDoublesPerAttempt() {
        val policy = ReconnectPolicy(enabled = true, baseDelayMs = 100, maxDelayMs = 10_000, jitterRatio = 0.0)
        val calc = BackoffCalculator(policy, Random(42))
        assertEquals(100L, calc.pureDelayMsForAttempt(1))
        assertEquals(200L, calc.pureDelayMsForAttempt(2))
        assertEquals(400L, calc.pureDelayMsForAttempt(3))
        assertEquals(1_600L, calc.pureDelayMsForAttempt(5))
    }

    @Test
    fun backoff_pureDelayCapsAtMaxDelay() {
        val policy = ReconnectPolicy(enabled = true, baseDelayMs = 100, maxDelayMs = 500, jitterRatio = 0.0)
        val calc = BackoffCalculator(policy, Random(7))
        assertEquals(400L, calc.pureDelayMsForAttempt(3))
        assertEquals(500L, calc.pureDelayMsForAttempt(4))
        assertEquals(500L, calc.pureDelayMsForAttempt(10))
        assertEquals(500L, calc.pureDelayMsForAttempt(20))
    }

    @Test
    fun backoff_invalidAttemptReturnsZero() {
        val policy = ReconnectPolicy(enabled = true, baseDelayMs = 100, maxDelayMs = 10_000)
        val calc = BackoffCalculator(policy)
        assertEquals(0L, calc.pureDelayMsForAttempt(0))
        assertEquals(0L, calc.pureDelayMsForAttempt(-5))
    }

    @Test
    fun backoff_disabledPolicyReturnsZero() {
        val disabled = ReconnectPolicy(enabled = false, baseDelayMs = 100, maxDelayMs = 10_000)
        val calc = BackoffCalculator(disabled)
        assertEquals(0L, calc.pureDelayMsForAttempt(1))
        assertEquals(0L, calc.delayMsForAttempt(1))
    }

    @Test
    fun backoff_jitteredDelayStaysWithinJitterBand() {
        val policy = ReconnectPolicy(enabled = true, baseDelayMs = 1_000, maxDelayMs = 16_000, jitterRatio = 0.2)
        val calc = BackoffCalculator(policy, Random(1_337))
        repeat(5) { attempt ->
            val delay = calc.delayMsForAttempt(attempt + 1)
            assertTrue("delay must be >= 1ms", delay >= 1L)
            assertTrue("delay must be <= max", delay <= policy.maxDelayMs + 1)
        }
        // Attempt 1 stays within ±20% of the pure base.
        val pure = calc.pureDelayMsForAttempt(1)
        val jittered = calc.delayMsForAttempt(1)
        val band = (pure * 0.2).toLong().coerceAtLeast(1L)
        assertTrue(
            "jittered $jittered must be within $band of $pure",
            kotlin.math.abs(jittered - pure) <= band,
        )
    }

    @Test
    fun backoff_jitterZero_isDeterministic() {
        val policy = ReconnectPolicy(enabled = true, baseDelayMs = 50, maxDelayMs = 500, jitterRatio = 0.0)
        val calc = BackoffCalculator(policy, Random(99))
        assertEquals(calc.pureDelayMsForAttempt(1), calc.delayMsForAttempt(1))
        assertEquals(calc.pureDelayMsForAttempt(2), calc.delayMsForAttempt(2))
    }

    // ================================================================
    // CdpTransport reconnect-aware connect overload
    // ================================================================

    @Test
    fun connectOverload_withPolicy_delegatesToPlainConnect() {
        val fake = FakeCdpTransport()
        fake.connect("ws://target", RecordingListener.NOOP, ReconnectPolicy(maxAttempts = 3))
        assertEquals("ws://target", fake.connectedUrl)
        assertTrue(fake.isConnected)
    }

    // ================================================================
    // Recovery under simulated socket drop
    // ================================================================

    @Test
    fun session_recoversAfterSocketDrop_andResubscribesDomains() {
        val fake = FakeCdpTransport()
        val policy = ReconnectPolicy(
            enabled = true,
            maxAttempts = 5,
            baseDelayMs = 2,
            maxDelayMs = 8,
            jitterRatio = 0.0,
            heartbeatIntervalMs = 0, // disabled for this test
            heartbeatTimeoutMs = 0,
        )
        val scheduler = newScheduler("test-reconnect")
        val session = CdpSession(fake, policy, BackoffCalculator(policy, Random(1)), scheduler, { System.currentTimeMillis() })
        val recording = RecordingListener()

        session.connect("ws://echo", recording)
        assertTrue(awaitTrue { fake.isConnected && recording.connectedEvents.get() == 1 })
        assertEquals(0, session.reconnectAttempts)

        // Domains are registered only after the initial connection is live,
        // mirroring the real "enable domain on connect" flow.
        session.registerDomain("Target")
        session.registerDomain("Network")
        val resubscribed = AtomicReference<Set<String>?>()
        session.onResubscribe = { resubscribed.set(it.toSet()) }

        // Simulate a socket drop.
        fake.deliverError(TransportException("socket dropped"))

        // Recovery: transport reconnects with exponential backoff, forwards
        // lifecycle events, and re-enables the registered domains.
        assertTrue("session must recover", awaitTrue { recording.connectedEvents.get() >= 2 && fake.isConnected })
        assertNotNull(resubscribed.get())
        assertEquals(setOf("Target", "Network"), resubscribed.get())
        assertEquals("backoff ladder resets after recovery", 0, session.reconnectAttempts)
        assertTrue("closed event forwarded on drop", recording.closedEvents.get() >= 1)
        assertTrue("error forwarded on drop", recording.errorEvents.get() >= 1)

        // The channel is usable again.
        session.send("""{"id":1,"method":"Empty"}""")
        assertTrue(fake.sentMessages.isNotEmpty())
        scheduler.shutdownNow()
    }

    @Test
    fun session_backoffResetsAfterRecovery() {
        val fake = FakeCdpTransport()
        val policy = ReconnectPolicy(enabled = true, maxAttempts = 10, baseDelayMs = 2, maxDelayMs = 8, jitterRatio = 0.0, heartbeatIntervalMs = 0)
        val scheduler = newScheduler("t-backoff-reset")
        val session = CdpSession(fake, policy, BackoffCalculator(policy, Random(2)), scheduler, { System.currentTimeMillis() })
        val recording = RecordingListener()

        session.connect("ws://127.0.0.1:9222", recording)
        assertTrue(awaitTrue { recording.connectedEvents.get() == 1 })
        assertEquals(0, session.lastAppliedBackoffMs)

        // First outage — attempt 1 uses the base delay.
        fake.deliverError(TransportException("drop 1"))
        assertTrue("must recover", awaitTrue { recording.connectedEvents.get() >= 2 && fake.isConnected })
        assertEquals(2L, session.lastAppliedBackoffMs)

        // Second outage — recovery reset the ladder, so backoff restarts at base.
        fake.deliverError(TransportException("drop 2"))
        assertTrue("must recover again", awaitTrue { recording.connectedEvents.get() >= 3 && fake.isConnected })
        assertEquals(2L, session.lastAppliedBackoffMs)
        scheduler.shutdownNow()
    }

    // ================================================================
    // Retry caps / exhaustion
    // ================================================================

    @Test
    fun session_retryCapIsEnforced_exhaustsAndStops() {
        val flaky = FlakyCdpTransport(failConnectAttempts = Int.MAX_VALUE)
        val policy = ReconnectPolicy(enabled = true, maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 4, jitterRatio = 0.0, heartbeatIntervalMs = 0)
        val scheduler = newScheduler("t-exhaust")
        val session = CdpSession(flaky, policy, BackoffCalculator(policy, Random(3)), scheduler, { System.currentTimeMillis() })
        val exited = AtomicInteger(0)
        session.onReconnectExhausted = { exited.incrementAndGet() }
        val recording = RecordingListener()

        session.connect("ws://127.0.0.1:9222", recording)

        assertTrue("must exhaust", awaitTrue { session.reconnectExhausted })
        assertTrue(awaitTrue { session.reconnectAttempts == 3 })

        assertEquals("1 initial + 3 capped retries", 4, flaky.connectCount.get())
        assertEquals("exhausted hook fires once", 1, exited.get())
        assertTrue(session.reconnectExhausted)
        assertFalse("no recovery when cap exhausted", flaky.isConnected)

        // A late drop must NOT resurrect the session.
        flaky.deliverError(TransportException("late drop"))
        Thread.sleep(50)
        assertEquals("no further connect attempts after exhaustion", 4, flaky.connectCount.get())
        scheduler.shutdownNow()
    }

    @Test
    fun session_reconnectDisabled_noRetryAfterDrop() {
        val fake = FakeCdpTransport()
        val disabled = ReconnectPolicy(enabled = false, baseDelayMs = 2, maxDelayMs = 8, heartbeatIntervalMs = 0)
        val scheduler = newScheduler("t-no-retry")
        val session = CdpSession(fake, disabled, BackoffCalculator(disabled, Random(3)), scheduler, { System.currentTimeMillis() })
        val recording = RecordingListener()
        session.connect("ws://127.0.0.1:9222", recording)
        assertTrue(awaitTrue { recording.connectedEvents.get() == 1 })

        fake.deliverError(TransportException("never retry"))
        Thread.sleep(60)
        assertEquals("no reconnect scheduled", 1, recording.connectedEvents.get())
        assertEquals(0, session.reconnectAttempts)
        assertFalse(fake.isConnected)
        scheduler.shutdownNow()
    }

    // ================================================================
    // Lifecycle event forwarding
    // ================================================================

    @Test
    fun session_forwardsLifecycleEventsToOuterListener() {
        val fake = FakeCdpTransport()
        val policy = ReconnectPolicy(enabled = true, maxAttempts = 5, baseDelayMs = 2, maxDelayMs = 8, jitterRatio = 0.0, heartbeatIntervalMs = 0)
        val scheduler = newScheduler("t-events")
        val session = CdpSession(fake, policy, BackoffCalculator(policy, Random(3)), scheduler, { System.currentTimeMillis() })
        val recording = RecordingListener()

        session.connect("ws://127.0.0.1:9222", recording)
        assertTrue(awaitTrue { recording.connectedEvents.get() == 1 })
        fake.deliverError(TransportException("forwarded-error"))
        assertTrue(awaitTrue { recording.errorEvents.get() == 1 && recording.closedEvents.get() == 1 })
        assertTrue("recovered", awaitTrue { recording.connectedEvents.get() >= 2 && fake.isConnected })

        fake.deliverMessage("""{"id":5,"result":{}}""")
        assertTrue(awaitTrue { recording.messages.isNotEmpty() })
        assertEquals("forwarded-error", session.disconnectReason)
        scheduler.shutdownNow()
    }

    @Test
    fun session_sendDelegatesToUnderlyingTransport() {
        val fake = FakeCdpTransport()
        val policy = ReconnectPolicy(enabled = true, maxAttempts = 5, heartbeatIntervalMs = 0)
        val scheduler = newScheduler("t-send")
        val session = CdpSession(fake, policy, BackoffCalculator(policy, Random(4)), scheduler, { System.currentTimeMillis() })
        session.connect("ws://127.0.0.1:9222", RecordingListener())
        assertTrue(awaitTrue { fake.isConnected })
        fake.resetSentMessages()
        session.send("""{"id":9,"method":"Browser.getVersion"}""")
        assertEquals(listOf("""{"id":9,"method":"Browser.getVersion"}"""), fake.sentMessages)
        scheduler.shutdownNow()
    }

    @Test
    fun session_sendWhenDisconnected_throws() {
        val session = CdpSession(EmptyCdpTransport(), ReconnectPolicy(enabled = true, heartbeatIntervalMs = 0), BackoffCalculator(ReconnectPolicy(enabled = true)))
        try {
            session.send("""{"id":1}""")
            org.junit.Assert.fail("Expected TransportException")
        } catch (e: TransportException) {
            assertTrue(e.message?.contains("not connected") == true)
        }
    }

    // ================================================================
    // Heartbeat monitoring
    // ================================================================

    @Test
    fun heartbeat_staleChannel_forcesReconnection() {
        val fake = FakeCdpTransport()
        val clock = AtomicLong(1_000L)
        val policy = ReconnectPolicy(enabled = true, maxAttempts = 5, baseDelayMs = 2, maxDelayMs = 8, jitterRatio = 0.0, heartbeatIntervalMs = 50, heartbeatTimeoutMs = 500)
        val scheduler = newScheduler("t-heartbeat")
        val session = CdpSession(fake, policy, BackoffCalculator(policy, Random(1)), scheduler, { clock.get() })
        val recording = RecordingListener()

        session.connect("ws://127.0.0.1:9222", recording)
        assertTrue(awaitTrue { recording.connectedEvents.get() == 1 && session.heartbeatActive })
        fake.deliverMessage("""{"id":1,"result":{}}""")

        // The channel is alive for now.
        clock.set(1_300L)
        session.checkHeartbeat()
        assertTrue("alive channel must not disconnect", fake.isConnected)

        // Silence exceeds the timeout.
        clock.set(2_000L)
        session.checkHeartbeat()
        assertTrue("stale channel must force disconnect+reconnect", awaitTrue { recording.connectedEvents.get() >= 2 && fake.isConnected })
        assertEquals("ladder resets on recovery", 0, session.reconnectAttempts)
        scheduler.shutdownNow()
    }

    @Test
    fun heartbeat_aliveChannel_neverDisconnects() {
        val fake = FakeCdpTransport()
        val clock = AtomicLong(1_000L)
        val policy = ReconnectPolicy(enabled = true, maxAttempts = 5, heartbeatIntervalMs = 50, heartbeatTimeoutMs = 1_000)
        val scheduler = newScheduler("t-alive")
        val session = CdpSession(fake, policy, BackoffCalculator(policy, Random(1)), scheduler, { clock.get() })
        val recording = RecordingListener()

        session.connect("ws://127.0.0.1:9222", recording)
        assertTrue(awaitTrue { recording.connectedEvents.get() == 1 })

        repeat(10) { i ->
            fake.deliverMessage("""{"id":$i,"result":{}}""")
            clock.addAndGet(200)
            session.checkHeartbeat()
        }
        assertTrue(fake.isConnected)
        assertEquals(1, recording.connectedEvents.get())
        scheduler.shutdownNow()
    }

    @Test
    fun heartbeat_disabledByZeroInterval_noHeartbeatMonitor() {
        val fake = FakeCdpTransport()
        val policy = ReconnectPolicy(enabled = true, maxAttempts = 3, heartbeatIntervalMs = 0, heartbeatTimeoutMs = 0)
        val scheduler = newScheduler("t-no-hb")
        val session = CdpSession(fake, policy, BackoffCalculator(policy, Random(5)), scheduler, { System.currentTimeMillis() })
        session.connect("ws://127.0.0.1:9222", RecordingListener.NOOP)
        assertTrue(awaitTrue { fake.isConnected })
        assertFalse("no heartbeat monitor when interval is 0", session.heartbeatActive)
        scheduler.shutdownNow()
    }

    // ================================================================
    // Lifecycle teardown (leak audit)
    // ================================================================

    @Test
    fun disconnect_cancelsScheduledReconnect_noFurtherAttempts() {
        val fake = FakeCdpTransport()
        // Generous delay ensures the scheduled reconnect never fires before teardown.
        val policy = ReconnectPolicy(enabled = true, maxAttempts = 10, baseDelayMs = 200, maxDelayMs = 400, jitterRatio = 0.0, heartbeatIntervalMs = 0)
        val scheduler = newScheduler("t-teardown")
        val session = CdpSession(fake, policy, BackoffCalculator(policy, Random(0)), scheduler, { System.currentTimeMillis() })
        val recording = RecordingListener()

        session.connect("ws://127.0.0.1:9222", recording)
        assertTrue(awaitTrue { recording.connectedEvents.get() == 1 })

        // Drop: schedules a reconnect.
        fake.deliverError(TransportException("drop"))
        assertTrue("reconnect scheduled", awaitTrue { session.reconnectAttempts >= 1 })

        // Terminal teardown must cancel the scheduled reconnect.
        session.disconnect()
        assertFalse(fake.isConnected)
        assertFalse(session.heartbeatActive)
        Thread.sleep(300)
        assertEquals("no further connects after disconnect", 1, recording.connectedEvents.get())
        scheduler.shutdownNow()
    }

    @Test
    fun disconnect_isIdempotentAndClearsOuterListener() {
        val fake = FakeCdpTransport()
        val scheduler = newScheduler("t-idempotent")
        val session = CdpSession(fake, ReconnectPolicy(enabled = true, heartbeatIntervalMs = 0), BackoffCalculator(ReconnectPolicy(enabled = true)), scheduler, { System.currentTimeMillis() })
        val recording = RecordingListener()
        session.connect("ws://127.0.0.1:9222", recording)
        assertTrue(awaitTrue { recording.connectedEvents.get() == 1 })

        session.disconnect()
        session.disconnect()
        session.disconnect()
        assertFalse(session.isConnected)

        // Late inbound events must not reach the detached outer listener.
        fake.deliverMessage("""{"id":1}""")
        assertEquals(1, recording.connectedEvents.get())
        scheduler.shutdownNow()
    }

    @Test
    fun session_recordsReconnectTelemetryInAuditEngine() {
        val telemetry = TelemetryEngine(initialEnabled = true)
        val fake = FakeCdpTransport()
        val policy = ReconnectPolicy(enabled = true, maxAttempts = 5, baseDelayMs = 2, maxDelayMs = 8, jitterRatio = 0.0, heartbeatIntervalMs = 0)
        val scheduler = newScheduler("t-telemetry")
        val session = CdpSession(fake, policy, BackoffCalculator(policy, Random(1)), scheduler, { System.currentTimeMillis() }, telemetry)
        val recording = RecordingListener()

        session.connect("ws://127.0.0.1:9222", recording)
        assertTrue(awaitTrue { recording.connectedEvents.get() == 1 })
        fake.deliverError(TransportException("drop"))
        assertTrue(awaitTrue { recording.connectedEvents.get() >= 2 && fake.isConnected })

        val reconnect = telemetry.snapshot().reconnect
        assertTrue("reconnect attempts recorded", reconnect.reconnectAttempts >= 1)
        assertTrue("successful reconnects recorded", reconnect.successfulReconnects >= 1)
        assertTrue("backoff delay recorded", reconnect.lastBackoffDelayMs > 0)
        scheduler.shutdownNow()
    }

    // ================================================================
    // Test helpers
    // ================================================================

    private fun newScheduler(name: String) =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, name).apply { isDaemon = true } }

    private fun awaitTrue(timeoutMs: Long = 4_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition()) return true
            } catch (_: Throwable) {
                // keep polling
            }
            Thread.sleep(5)
        }
        return condition()
    }

    private class RecordingListener : CdpTransport.Listener {
        val connectedEvents = AtomicInteger(0)
        val closedEvents = AtomicInteger(0)
        val errorEvents = AtomicInteger(0)
        val messages = CopyOnWriteArrayList<String>()

        override fun onConnected() { connectedEvents.incrementAndGet() }
        override fun onMessage(rawJson: String) { messages.add(rawJson) }
        override fun onClosed(reason: String) { closedEvents.incrementAndGet() }
        override fun onError(error: TransportException) { errorEvents.incrementAndGet() }

        companion object {
            val NOOP = object : CdpTransport.Listener {
                override fun onConnected() {}
                override fun onMessage(rawJson: String) {}
                override fun onClosed(reason: String) {}
                override fun onError(error: TransportException) {}
            }
        }
    }

    /** A transport whose `connect` fails for the first [failConnectAttempts] attempts. */
    private class FlakyCdpTransport(
        private val failConnectAttempts: Int = Int.MAX_VALUE,
    ) : CdpTransport {
        val connectCount = AtomicInteger(0)
        private val connectedFlag = AtomicBoolean(false)
        private var listener: CdpTransport.Listener? = null

        override val isConnected: Boolean get() = connectedFlag.get()

        override fun connect(url: String, listener: CdpTransport.Listener) {
            this.listener = listener
            val attempt = connectCount.incrementAndGet()
            if (attempt <= failConnectAttempts) {
                connectedFlag.set(false)
                listener.onError(TransportException("connect attempt $attempt failed"))
                listener.onClosed("connect attempt $attempt failed")
            } else {
                connectedFlag.set(true)
                listener.onConnected()
            }
        }

        override fun send(rawMessage: String) {
            if (!connectedFlag.get()) throw TransportException("Cannot send: transport is not connected")
        }

        override fun disconnect() {
            connectedFlag.set(false)
            listener?.onClosed("disconnected")
        }

        /** Simulates a socket drop after a successful (or failed) connect. */
        fun deliverError(error: TransportException) {
            connectedFlag.set(false)
            listener?.onError(error)
            listener?.onClosed(error.message ?: "transport error")
        }
    }

    /** Minimal transport whose `send` always fails (exercises error delegation). */
    private class EmptyCdpTransport : CdpTransport {
        private val connectedFlag = AtomicBoolean(false)
        override val isConnected: Boolean get() = connectedFlag.get()
        override fun connect(url: String, listener: CdpTransport.Listener) {
            connectedFlag.set(true)
            listener.onConnected()
        }
        override fun send(rawMessage: String) {
            throw TransportException("Cannot send: transport is not connected")
        }
        override fun disconnect() { connectedFlag.set(false) }
    }
}