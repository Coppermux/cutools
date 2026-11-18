package com.coppermux.cutools.core.kernel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticKernelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun kernel() = DiagnosticKernel(dispatcher = testDispatcher, clock = { 42L })

    private fun DiagnosticKernel.attach(target: String = "t1", session: String = "s1") {
        dispatch(KernelEvent.ConnectRequested)
        dispatch(KernelEvent.ConnectionEstablished)
        dispatch(KernelEvent.TargetAttached(target, session))
    }

    @Test
    fun initialState_isIdle_epochZero() {
        val k = kernel()
        assertEquals(KernelSnapshot.INITIAL, k.state.value)
        assertEquals(KernelState.IDLE, k.snapshot.state)
        assertNull(k.currentSession)
        assertFalse(k.hasActiveSession)
    }

    @Test
    fun stateFlow_publishesImmutableSnapshots() {
        val k = kernel()
        val before = k.state.value
        k.attach()
        val after = k.state.value
        assertEquals(KernelState.IDLE, before.state) // previous snapshot untouched
        assertEquals(KernelState.ATTACHED, after.state)
        assertEquals("dk-1", k.currentSession?.sessionKey)
        assertEquals(42L, k.currentSession?.establishedAtMillis)
        assertTrue(k.isCurrent(1L))
        assertFalse(k.isCurrent(0L))
    }

    @Test
    fun dispatch_returnsResultsForAppliedIgnoredRejected() {
        val k = kernel()
        assertTrue(k.dispatch(KernelEvent.TargetAttached("t", "s")) is TransitionResult.Rejected)
        assertTrue(k.dispatch(KernelEvent.ConnectionLost("x")) is TransitionResult.Ignored)
        val applied = k.dispatch(KernelEvent.ConnectRequested)
        assertEquals(
            TransitionResult.Applied(KernelState.IDLE, KernelState.CONNECTING, 0L, 0L),
            applied,
        )
    }

    @Test
    fun concurrentDispatch_isSerialized_noLostTransitions_noCompetingSessions() {
        val k = kernel()
        k.dispatch(KernelEvent.ConnectRequested)
        k.dispatch(KernelEvent.ConnectionEstablished)
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        val applied = AtomicInteger(0)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                barrier.await()
                repeat(perThread) { i ->
                    // Every event is a distinct replacement attachment: always Applied, epoch +1.
                    val r = k.dispatch(KernelEvent.TargetAttached("t$t", "s$t-$i"))
                    if (r is TransitionResult.Applied) applied.incrementAndGet()
                    KernelReducerTest.assertInvariants(k.snapshot)
                }
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(threads * perThread, applied.get())
        assertEquals((threads * perThread).toLong(), k.currentEpoch)
        assertEquals(KernelState.ATTACHED, k.snapshot.state)
        KernelReducerTest.assertInvariants(k.snapshot)
    }

    @Test
    fun epochIsMonotonic_asObservedByConcurrentReaders() {
        val k = kernel()
        k.attach()
        val pool = Executors.newFixedThreadPool(2)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val violations = AtomicInteger(0)
        pool.execute {
            var last = -1L
            while (!stop.get()) {
                val e = k.state.value.epoch
                if (e < last) violations.incrementAndGet()
                last = e
            }
        }
        repeat(2_000) { k.invalidateSession("n$it") }
        stop.set(true)
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(0, violations.get())
        assertEquals(2_001L, k.currentEpoch)
    }

    @Test
    fun launchInSession_isCancelled_whenSessionSuperseded() = testScope.runTest {
        val k = kernel()
        k.attach()
        val started = CompletableDeferred<Unit>()
        val job = k.launchInSession(1L) {
            started.complete(Unit)
            awaitCancellation()
        }
        assertNotNull(job)
        runCurrent()
        assertTrue(started.isCompleted)
        assertTrue(job!!.isActive)

        k.dispatch(KernelEvent.TargetAttached("t2", "s2")) // replacement → epoch 2
        runCurrent()
        assertTrue(job.isCancelled)

        // Work for a stale epoch is never launched.
        assertNull(k.launchInSession(1L) { })
        assertNotNull(k.launchInSession(2L) { })
    }

    @Test
    fun launchInSession_cancelledOnDetach_loss_reset_andInvalidation() = testScope.runTest {
        val supersede: List<(DiagnosticKernel) -> Unit> = listOf(
            { it.dispatch(KernelEvent.TargetDetached("t1")) },
            { it.dispatch(KernelEvent.ConnectionLost("drop")) },
            { it.dispatch(KernelEvent.ConnectionFailed("err")) },
            { it.dispatch(KernelEvent.SessionsCleared("clear")) },
            { it.beginReset("reset") },
            { it.invalidateSession("nav") },
        )
        supersede.forEach { action ->
            val k = kernel()
            k.attach()
            val job = k.launchInSession(k.currentEpoch) { awaitCancellation() }!!
            runCurrent()
            action(k)
            runCurrent()
            assertTrue("job should be cancelled", job.isCancelled)
        }
    }

    @Test
    fun launchInSession_returnsNull_withoutSession() {
        val k = kernel()
        assertNull(k.launchInSession(0L) { })
        k.dispatch(KernelEvent.ConnectRequested)
        assertNull(k.launchInSession(0L) { })
    }

    @Test
    fun beginReset_returnsTicket_andSecondBeginIsRejected() {
        val k = kernel()
        k.attach()
        val ticket = k.beginReset("first")
        assertNotNull(ticket)
        assertEquals(2L, ticket!!.resetEpoch)
        assertEquals(KernelState.RESETTING, k.snapshot.state)
        assertNull(k.currentSession)
        assertNull(k.beginReset("second"))

        val result = k.completeReset(ticket)
        assertTrue(result is TransitionResult.Applied)
        assertEquals(KernelState.ATTACHED, k.snapshot.state)
        assertEquals(3L, k.currentEpoch)
        assertEquals("dk-3", k.currentSession?.sessionKey)

        // Completing the same ticket twice is rejected and changes nothing.
        assertTrue(k.completeReset(ticket) is TransitionResult.Rejected)
        assertEquals(3L, k.currentEpoch)
    }

    @Test
    fun suspendReset_completesEvenWhenBlockThrows() = testScope.runTest {
        val k = kernel()
        k.attach()
        val thrown = runCatching { k.reset("boom") { error("cleanup failed") } }
        assertTrue(thrown.isFailure)
        assertEquals(KernelState.ATTACHED, k.snapshot.state)
        assertNull(k.snapshot.resetTicket)
    }

    @Test
    fun suspendReset_cancellation_doesNotLeavePartiallyCommittedState() = testScope.runTest {
        val k = kernel()
        k.attach()
        val inBlock = CompletableDeferred<Unit>()
        val job = launch {
            k.reset("cancel-me") {
                inBlock.complete(Unit)
                awaitCancellation()
            }
        }
        runCurrent()
        assertTrue(inBlock.isCompleted)
        assertEquals(KernelState.RESETTING, k.snapshot.state)

        job.cancelAndJoin()

        val s = k.snapshot
        assertEquals(KernelState.ATTACHED, s.state)
        assertNull(s.resetTicket)
        assertEquals(3L, s.epoch)
        KernelReducerTest.assertInvariants(s)
        // A new reset can start afterwards.
        assertNotNull(k.beginReset("again"))
    }

    @Test
    fun suspendReset_rejected_whenAlreadyResetting() = testScope.runTest {
        val k = kernel()
        k.beginReset("held")
        val r = runCatching { k.reset("nested") { } }
        assertTrue(r.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun shutdown_cancelsSessionWork_andRejectsFurtherEvents() = testScope.runTest {
        val k = kernel()
        k.attach()
        val job = k.launchInSession(1L) { awaitCancellation() }!!
        runCurrent()
        k.shutdown()
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(k.dispatch(KernelEvent.ConnectionLost("x")) is TransitionResult.Rejected)
        assertNull(k.beginReset("x"))
        assertNull(k.launchInSession(1L) { })
    }
}
