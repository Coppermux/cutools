package com.coppermux.cutools.core.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 15 — coroutine scope & leak audit tests for [LifecycleWatcher].
 *
 * Verifies that scope closure cancels every tracked child job, runs registered
 * cleanup exactly once (idempotent close), rejects late registrations without
 * leaking them, and recursively closes child watchers.
 */
class LifecycleWatcherTest {

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ================================================================
    // Child job cancellation
    // ================================================================

    @Test
    fun close_cancelsTrackedChildJobs() {
        val scope = newScope()
        val lifecycle = LifecycleWatcher()
        val gate = CompletableDeferred<Unit>()
        val proceeded = java.util.concurrent.atomic.AtomicBoolean(false)

        val job = lifecycle.track(
            scope.launch {
                gate.await()
                proceeded.set(true)
            }
        )

        lifecycle.close()

        assertTrue("child must be cancelled on scope closure", job.isCancelled)
        assertTrue("cancelled child must never proceed past its suspend gate", !proceeded.get())
        scope.cancel()
    }

    @Test
    fun close_cancelsMultipleTrackedJobs() = runBlocking {
        val scope = newScope()
        val lifecycle = LifecycleWatcher()
        val gate = CompletableDeferred<Unit>()
        val jobs = (1..10).map { lifecycle.track(scope.launch { gate.await() }) }
        lifecycle.close()
        jobs.forEach { assertTrue("job $it must be cancelled", it.isCancelled) }
        scope.cancel()
    }

    // ================================================================
    // Cleanup callbacks
    // ================================================================

    @Test
    fun close_runsCleanupExactlyOnce() {
        val lifecycle = LifecycleWatcher()
        val calls = AtomicInteger(0)
        lifecycle.onClose { calls.incrementAndGet() }
        lifecycle.close()
        lifecycle.close()
        lifecycle.close()
        assertEquals(1, calls.get())
    }

    @Test
    fun close_runsAllRegisteredCleanups() {
        val lifecycle = LifecycleWatcher()
        val calls = AtomicInteger(0)
        repeat(5) { lifecycle.onClose { calls.incrementAndGet() } }
        lifecycle.close()
        assertEquals(5, calls.get())
    }

    // ================================================================
    // Late registrations after close
    // ================================================================

    @Test
    fun track_afterClose_cancelsJobImmediately() = runBlocking {
        val scope = newScope()
        val lifecycle = LifecycleWatcher()
        lifecycle.close()
        val job = lifecycle.track(scope.launch { Unit })
        assertTrue(job.isCancelled)
        scope.cancel()
    }

    @Test
    fun onClose_afterClose_runsActionImmediately() {
        val lifecycle = LifecycleWatcher()
        lifecycle.close()
        val calls = AtomicInteger(0)
        lifecycle.onClose { calls.incrementAndGet() }
        assertEquals("late cleanup must run immediately, not leak", 1, calls.get())
    }

    @Test
    fun isClosed_flipsAfterClose() {
        val lifecycle = LifecycleWatcher()
        assertTrue(!lifecycle.isClosed)
        lifecycle.close()
        assertTrue(lifecycle.isClosed)
    }

    // ================================================================
    // Child watchers
    // ================================================================

    @Test
    fun close_recursivelyClosesChildWatchers() {
        val parent = LifecycleWatcher()
        val child1 = LifecycleWatcher()
        val child2 = LifecycleWatcher()
        parent.addChild(child1)
        parent.addChild(child2)

        val childCalls = AtomicInteger(0)
        child1.onClose { childCalls.incrementAndGet() }
        child2.onClose { childCalls.incrementAndGet() }

        parent.close()
        assertTrue(child1.isClosed)
        assertTrue(child2.isClosed)
        assertEquals(2, childCalls.get())
    }

    @Test
    fun addChild_afterParentClose_closesChildImmediately() {
        val parent = LifecycleWatcher()
        parent.close()
        val lateChild = LifecycleWatcher()
        parent.addChild(lateChild)
        assertTrue(lateChild.isClosed)
    }

    // ================================================================
    // Thread safety
    // ================================================================

    @Test
    fun concurrentTrackAndClose_neverLeaksOrDoubleRuns() {
        val lifecycle = LifecycleWatcher()
        val runCleanup = AtomicInteger(0)
        val threads = 4
        val perThread = 1_000
        val errors = java.util.concurrent.atomic.AtomicInteger(0)

        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val latch = java.util.concurrent.CountDownLatch(threads)

        repeat(threads) { t ->
            pool.execute {
                try {
                    repeat(perThread) { i ->
                        if (i % 10 == 0) {
                            lifecycle.onClose { runCleanup.incrementAndGet() }
                        } else {
                            lifecycle.track(Job())
                        }
                    }
                    if (t == 0) {
                        lifecycle.close()
                    }
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(30, java.util.concurrent.TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(0, errors.get())
        assertTrue(lifecycle.isClosed)
    }

    // ================================================================
    // Integration: engine-style collector shutdown via watcher
    // ================================================================

    @Test
    fun watcherTiesCollectorJobAndShutdownTogether() = runBlocking {
        val scope = newScope()
        val lifecycle = LifecycleWatcher()
        val shutdownRan = AtomicInteger(0)
        val collector = lifecycle.track(scope.launch { withContext(Dispatchers.Default) { Unit } })

        lifecycle.onClose { shutdownRan.incrementAndGet() }

        // Scope closure cancels the collector AND runs the engine teardown.
        lifecycle.close()

        assertTrue(collector.isCancelled)
        assertEquals(1, shutdownRan.get())
        scope.cancel()
    }
}