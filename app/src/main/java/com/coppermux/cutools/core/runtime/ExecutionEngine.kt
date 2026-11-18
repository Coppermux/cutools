package com.coppermux.cutools.core.runtime

import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "CuTools/ExecutionEngine"

private fun logTrace(tag: String, message: String) {
    try {
        android.util.Log.d(tag, message)
    } catch (_: Throwable) {
    }
    println("[$tag] $message")
}

/** Listener for JavaScript execution state / outcome changes. */
interface ExecutionEngineListener {
    fun onExecutionStateChanged(state: ExecutionState)
    fun onEvaluationCompleted(outcome: EvaluationOutcome)
}

/**
 * Orchestrates genuine Chrome-backed JavaScript execution against the currently
 * attached page target.
 *
 * - Executes the [EvaluationRequest] via the real CDP `Runtime.evaluate`
 *   command through the shared [CommandDispatcher] on the active session.
 * - The browser performs all execution; CuTools never interprets JavaScript
 *   locally and never simulates results.
 * - Ties every outcome to its session/target and drops responses that no longer
 *   belong to the active target (generation guard), so a pending evaluation can
 *   never be silently attributed to a different target.
 * - Exposes explicit, UI-independent [ExecutionState] and notifies
 *   [ExecutionEngineListener]s on state/outcome changes.
 *
 * All Chrome communication reuses DevTools Core; no second transport, WebSocket,
 * or dispatcher is created.
 *
 * Threading: state is mutated on a single-thread executor; listeners are invoked
 * on that executor thread, so UI callers must marshal onto the Android UI thread.
 */
class ExecutionEngine(
    private var dispatcher: CommandDispatcher,
    private val dispatcherProvider: (() -> CommandDispatcher)? = null,
    private val executor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cutools-exec").apply { isDaemon = true }
    },
) {

    /** The currently active dispatcher (dynamic via provider, or current reference). */
    val activeDispatcher: CommandDispatcher
        get() = dispatcherProvider?.invoke() ?: dispatcher

    /** Rebinds to a fresh [CommandDispatcher] across reconnects. */
    fun rebind(newDispatcher: CommandDispatcher) {
        dispatcher = newDispatcher
    }

    /** Flushes any queued work on the internal executor (for deterministic synchronization). */
    fun drain(timeoutMs: Long = 3000): Boolean {
        if (executor.isShutdown) return true
        return try {
            executor.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Read-only execution state. */
    @Volatile
    var state: ExecutionState = ExecutionState.empty()
        private set

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    private val listeners = CopyOnWriteArrayList<ExecutionEngineListener>()

    /** Guards stale responses after detach / re-bind. */
    private val generation = AtomicInteger(0)

    /** Local sequence for evaluation outcome ids. */
    private val outcomeId = AtomicLong(0)

    private val pendingByGeneration = mutableMapOf<Int, Int>()

    fun addListener(listener: ExecutionEngineListener) { listeners.add(listener) }
    fun removeListener(listener: ExecutionEngineListener) { listeners.remove(listener) }

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session, making it the evaluation target. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        logTrace(TAG, "bindSession targetId=${newSession.targetId} sessionId=${newSession.sessionId}")
        executor.execute {
            pendingByGeneration.clear()
            updateState(
                ExecutionState(
                    sessionId = newSession.sessionId,
                    targetId = newSession.targetId,
                    phase = ExecutionPhase.READY,
                    pendingEvaluations = 0,
                )
            )
        }
    }

    /** Detaches, invalidating in-flight evaluations without issuing commands. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        logTrace(TAG, "detach session cleared")
        executor.execute {
            pendingByGeneration.clear()
            updateState(ExecutionState.empty())
        }
    }

    /**
     * Clears pending evaluations and increments generation to invalidate in-flight callbacks.
     */
    fun clear() {
        val targetGen = generation.incrementAndGet()
        executor.execute {
            pendingByGeneration.keys.retainAll { it >= targetGen }
            val currentPending = pendingByGeneration.getOrDefault(generation.get(), 0)
            updateState(state.copy(pendingEvaluations = currentPending))
        }
    }

    /** Handles connection loss. */
    fun onConnectionLost() {
        detach()
    }

    /** Shuts down the internal executor. */
    fun shutdown() {
        executor.shutdownNow()
    }

    // ================================================================
    // Evaluation
    // ================================================================

    /**
     * Executes [request] against the currently selected page target.
     *
     * The callback receives an [EvaluationOutcome] distinguishing success,
     * evaluation exception, and protocol/command failure. If the engine is not
     * bound, a [EvaluationResultKind.ProtocolError] outcome is produced (an
     * honest local validation, not a simulated browser result).
     */
    fun evaluate(request: EvaluationRequest, callback: (EvaluationOutcome) -> Unit) {
        val gen = generation.get()
        val sess = session
        logTrace(TAG, "evaluate requested: expression='${request.expression}' session=${sess?.sessionId}")
        if (sess == null || request.expression.isBlank()) {
            executor.execute {
                if (!isCurrent(gen)) return@execute
                val outcome = EvaluationOutcome(
                    id = outcomeId.incrementAndGet(),
                    request = request,
                    result = EvaluationResultKind.ProtocolError(
                        ConnectionException("Not attached to a target")
                    ),
                    sessionId = sess?.sessionId ?: "",
                    targetId = sess?.targetId,
                )
                logTrace(TAG, "evaluate protocol error outcome: ${outcome.result}")
                val currentPending = pendingByGeneration.getOrDefault(gen, 0)
                updateState(state.copy(pendingEvaluations = currentPending, lastOutcome = outcome))
                notifyCompleted(outcome)
                callback(outcome)
            }
            return
        }

        executor.execute {
            if (isCurrent(gen)) {
                val current = pendingByGeneration.getOrDefault(gen, 0) + 1
                pendingByGeneration[gen] = current
                updateState(state.copy(pendingEvaluations = current))
            }
        }

        activeDispatcher.sendCommand(
            method = "Runtime.evaluate",
            params = request.toParams(),
            sessionId = sess.sessionId,
        ) { result ->
            executor.execute {
                val genCount = pendingByGeneration.getOrDefault(gen, 0)
                if (genCount > 0) {
                    if (genCount == 1) {
                        pendingByGeneration.remove(gen)
                    } else {
                        pendingByGeneration[gen] = genCount - 1
                    }
                }

                // A response for a superseded target/generation is dropped so it
                // can never be attributed to a different target or mutate active pending counts.
                if (!isCurrent(gen)) {
                    return@execute
                }

                val currentPending = pendingByGeneration.getOrDefault(gen, 0)
                val outcome = when (result) {
                    is CommandResult.Success -> EvaluationResultMapper
                        .mapSuccess(result.response, request, sess.sessionId, sess.targetId)
                        .copy(id = outcomeId.incrementAndGet())
                    is CommandResult.Failure -> EvaluationOutcome(
                        id = outcomeId.incrementAndGet(),
                        request = request,
                        result = EvaluationResultKind.ProtocolError(result.error),
                        sessionId = sess.sessionId,
                        targetId = sess.targetId,
                    )
                }
                logTrace(TAG, "evaluate completed: outcome=${outcome.result} displayText='${outcome.displayText}'")
                updateState(state.copy(pendingEvaluations = currentPending, lastOutcome = outcome))
                notifyCompleted(outcome)
                callback(outcome)
            }
        }
    }

    companion object {
        const val DEFAULT_EVALUATION_TIMEOUT_MS: Long = 10_000L
    }

    // ================================================================
    // State / notification helpers
    // ================================================================

    private fun updateState(newState: ExecutionState) {
        state = newState
        listeners.forEach { it.onExecutionStateChanged(newState) }
    }

    private fun notifyCompleted(outcome: EvaluationOutcome) {
        listeners.forEach { it.onEvaluationCompleted(outcome) }
    }

    private fun isCurrent(gen: Int): Boolean = generation.get() == gen
}
