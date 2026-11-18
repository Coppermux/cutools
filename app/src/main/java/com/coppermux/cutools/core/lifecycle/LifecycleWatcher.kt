package com.coppermux.cutools.core.lifecycle

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job

/**
 * Phase 15 — coroutine scope & leak-audit primitive.
 *
 * Tracks child [Job]s and teardown callbacks owned by a ViewModel, engine
 * collector, or session wrapper so that scope closure ([close]) cancels every
 * tracked child exactly once. [close] is idempotent: repeated calls never
 * double-cancel and never re-run cleanup.
 *
 * Typical wiring:
 * ```
 * val lifecycle = LifecycleWatcher()
 * lifecycle.track(scope.launch { engine.state.collect { ... } })
 * lifecycle.onClose { engine.shutdown() }
 * lifecycle.close()  // cancels the collector job + runs engine.shutdown()
 * ```
 *
 * Thread safety: all bookkeeping is guarded; [close] may be called from any
 * thread and remains safe against concurrent [track]/[onClose] registrations.
 */
class LifecycleWatcher {

    private val lock = Any()
    private val jobs = mutableListOf<Job>()
    private val closeActions = mutableListOf<() -> Unit>()
    private val childWatchers = mutableListOf<LifecycleWatcher>()
    private val closedFlag = AtomicBoolean(false)

    /** Whether [close] has already been invoked. */
    val isClosed: Boolean get() = closedFlag.get()

    /**
     * Registers [job] for cancellation on [close]. If the watcher is already
     * closed, the job is cancelled immediately (a late registration must never
     * leak).
     */
    fun track(job: Job): Job {
        val cancelNow = synchronized(lock) {
            if (closedFlag.get()) {
                true
            } else {
                jobs.add(job)
                false
            }
        }
        if (cancelNow) {
            runCatching { job.cancel() }
        }
        return job
    }

    /**
     * Registers [action] to run once on [close]. If the watcher is already
     * closed, the action runs immediately (cleanup must never be dropped).
     */
    fun onClose(action: () -> Unit) {
        val runNow = synchronized(lock) {
            if (closedFlag.get()) {
                true
            } else {
                closeActions.add(action)
                false
            }
        }
        if (runNow) {
            runCatching { action() }
        }
    }

    /**
     * Registers [watcher] as a child; closing this watcher also closes the
     * child. A child registered after this watcher is closed is closed
     * immediately.
     */
    fun addChild(watcher: LifecycleWatcher) {
        val closeNow = synchronized(lock) {
            if (closedFlag.get()) {
                true
            } else {
                childWatchers.add(watcher)
                false
            }
        }
        if (closeNow) {
            watcher.close()
        }
    }

    /**
     * Cancels every tracked child job, runs every registered cleanup exactly
     * once, and closes all child watchers. Idempotent and safe from any thread.
     */
    fun close() {
        val snapshot = synchronized(lock) {
            if (closedFlag.getAndSet(true)) {
                return
            }
            val cancelled = jobs.toList()
            jobs.clear()
            val actions = closeActions.toList()
            closeActions.clear()
            val children = childWatchers.toList()
            childWatchers.clear()
            CancellationSnapshot(cancelled, actions, children)
        }
        snapshot.jobs.forEach { runCatching { it.cancel() } }
        snapshot.actions.forEach { runCatching { it() } }
        snapshot.children.forEach { it.close() }
    }

    private data class CancellationSnapshot(
        val jobs: List<Job>,
        val actions: List<() -> Unit>,
        val children: List<LifecycleWatcher>,
    )
}