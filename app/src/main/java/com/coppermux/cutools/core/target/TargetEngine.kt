package com.coppermux.cutools.core.target

import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.models.TargetActionResult
import com.coppermux.cutools.core.target.models.TargetCapabilityStatus
import com.coppermux.cutools.core.target.models.TargetError
import com.coppermux.cutools.core.target.models.TargetInfoModel
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates real-time Chrome target discovery, attachment, and lifecycle
 * tracking via the CDP `Target` domain.
 *
 * Phase 12 — Security Extensions & Target Domain Integration Engine:
 * - [setDiscoverTargets] / [getTargets] drive target discovery.
 * - [attachToTarget] / [detachFromTarget] manage multi-session target switching.
 * - The engine registers `Target.targetCreated`, `Target.targetDestroyed`, and
 *   `Target.targetInfoChanged` listeners, ingesting genuine target lifecycle
 *   events into a real-time [targets] `StateFlow` — never fabricating any of it
 *   (No-Fake-Capability rule).
 * - Session attach/detach lifecycle resets all target state and re-binds event
 *   subscriptions; a generation counter rejects stale async results/events
 *   delivered after detach.
 * - Protocol failures, missing sessions, unavailable methods, and connection
 *   loss each resolve to an explicit [TargetActionResult.Failure] carrying a
 *   [TargetError] (`Unsupported` / `Unavailable` / `TargetDisconnected` /
 *   `ExecutionFailed`).
 *
 * Threading: all shared state is `@Volatile`; the target log is a
 * [CopyOnWriteArrayList] so concurrent event streams never lose an update. All
 * Chrome communication reuses the shared [CommandDispatcher].
 */
class TargetEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `Target` domain capability for the current connection. */
    @Volatile
    var capability: TargetCapabilityStatus? = null
        private set

    /** Real-time stream of discovered targets, in discovery order. */
    private val _targets = MutableStateFlow<List<TargetInfoModel>>(emptyList())
    val targets: StateFlow<List<TargetInfoModel>> = _targets

    /** Guards stale async results/events after detach / re-bind. */
    private val generation = AtomicInteger(0)

    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    private val targetLog = CopyOnWriteArrayList<TargetInfoModel>()

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session, resets prior target state, and re-binds subscriptions. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        resetState()
        clearSubscriptions()
        registerSubscriptions()
    }

    /** Detaches, clearing all target state and subscriptions. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        resetState()
        clearSubscriptions()
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    /** Releases all event subscriptions (no listener leaks). */
    fun shutdown() {
        clearSubscriptions()
    }

    // ================================================================
    // Target domain lifecycle
    // ================================================================

    /**
     * Enables/disables target discovery notifications via
     * `Target.setDiscoverTargets`.
     */
    fun setDiscoverTargets(
        discover: Boolean,
        callback: (TargetActionResult<Unit>) -> Unit = {},
    ) {
        val sess = session
        if (sess == null) {
            capability = TargetCapabilityStatus.UNAVAILABLE
            callback(TargetActionResult.Failure(TargetError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Target.setDiscoverTargets",
            params = mapOf("discover" to discover),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = TargetCapabilityStatus.SUPPORTED
                    callback(TargetActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(TargetActionResult.Failure(classifyFailure(result.error, "Target.setDiscoverTargets")))
                }
            }
        }
    }

    /**
     * Lists all current targets via `Target.getTargets`, caching the genuine
     * list into the [targets] stream.
     */
    fun getTargets(callback: (TargetActionResult<List<TargetInfoModel>>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = TargetCapabilityStatus.UNAVAILABLE
            callback(TargetActionResult.Failure(TargetError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Target.getTargets",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
is CommandResult.Success -> {
                    val raw = result.response.result
                    if (raw !is Map<*, *>) {
                        capability = TargetCapabilityStatus.FAILED
                        callback(TargetActionResult.Failure(TargetError.ExecutionFailed("Target.getTargets returned a non-object result")))
                    } else {
                        val parsed = TargetParser.parseGetTargets(raw)
                        upsertAll(parsed)
                        capability = TargetCapabilityStatus.SUPPORTED
                        callback(TargetActionResult.Success(parsed))
                    }
                }
                is CommandResult.Failure -> {
                    callback(TargetActionResult.Failure(classifyFailure(result.error, "Target.getTargets")))
                }
            }
        }
    }

    /**
     * Attaches to [targetId] via `Target.attachToTarget`, resolving the CDP
     * `sessionId` on success.
     */
    fun attachToTarget(
        targetId: String,
        flatten: Boolean = true,
        callback: (TargetActionResult<String>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = TargetCapabilityStatus.UNAVAILABLE
            callback(TargetActionResult.Failure(TargetError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Target.attachToTarget",
            params = mapOf("targetId" to targetId, "flatten" to flatten),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val sessionId = extractSessionId(result.response.result)
                    if (sessionId == null) {
                        capability = TargetCapabilityStatus.FAILED
                        callback(TargetActionResult.Failure(TargetError.ExecutionFailed("attachToTarget response missing sessionId")))
                    } else {
                        capability = TargetCapabilityStatus.SUPPORTED
                        callback(TargetActionResult.Success(sessionId))
                    }
                }
                is CommandResult.Failure -> {
                    callback(TargetActionResult.Failure(classifyFailure(result.error, "Target.attachToTarget")))
                }
            }
        }
    }

    /**
     * Detaches from a target session via `Target.detachFromTarget`.
     */
    fun detachFromTarget(
        sessionId: String,
        callback: (TargetActionResult<Unit>) -> Unit = {},
    ) {
        val sess = session
        if (sess == null) {
            capability = TargetCapabilityStatus.UNAVAILABLE
            callback(TargetActionResult.Failure(TargetError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Target.detachFromTarget",
            params = mapOf("sessionId" to sessionId),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = TargetCapabilityStatus.SUPPORTED
                    callback(TargetActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(TargetActionResult.Failure(classifyFailure(result.error, "Target.detachFromTarget")))
                }
            }
        }
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("Target.targetCreated") { event -> onTargetCreated(event) }
        subscriptions += eventRouter.subscribe("Target.targetDestroyed") { event -> onTargetDestroyed(event) }
        subscriptions += eventRouter.subscribe("Target.targetInfoChanged") { event -> onTargetInfoChanged(event) }
    }

    /** Applies a `Target.targetCreated` event if it belongs to the active session. */
    private fun onTargetCreated(event: Event) {
        val active = session ?: return
        if (event.sessionId != null && event.sessionId != active.sessionId) return
        val params = event.params as? Map<*, *> ?: return
        val info = TargetParser.parseTargetInfo(params["targetInfo"] ?: params) ?: return
        upsert(info)
    }

    /** Applies a `Target.targetDestroyed` event if it belongs to the active session. */
    private fun onTargetDestroyed(event: Event) {
        val active = session ?: return
        if (event.sessionId != null && event.sessionId != active.sessionId) return
        val targetId = TargetParser.parseDestroyedTargetId(event.params) ?: return
        removeTarget(targetId)
    }

    /** Applies a `Target.targetInfoChanged` event if it belongs to the active session. */
    private fun onTargetInfoChanged(event: Event) {
        val active = session ?: return
        if (event.sessionId != null && event.sessionId != active.sessionId) return
        val params = event.params as? Map<*, *> ?: return
        val info = TargetParser.parseTargetInfo(params["targetInfo"] ?: params) ?: return
        upsert(info)
    }

    private fun upsertAll(incoming: List<TargetInfoModel>) {
        incoming.forEach { upsert(it) }
    }

    private fun upsert(info: TargetInfoModel) {
        val existing = targetLog.find { it.targetId == info.targetId }
        if (existing != null) {
            targetLog.remove(existing)
        }
        targetLog.add(info)
        _targets.value = targetLog.toList()
    }

    private fun removeTarget(targetId: String) {
        val existing = targetLog.find { it.targetId == targetId }
        if (existing != null) {
            targetLog.remove(existing)
            _targets.value = targetLog.toList()
        }
    }

    // ================================================================
    // Error mapping & state helpers
    // ================================================================

    /**
     * Maps a CDP command failure to an explicit [TargetError]. A
     * method-not-found error (`-32601`) is [TargetError.Unsupported]; a
     * connection failure is [TargetError.TargetDisconnected]; everything else
     * is [TargetError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): TargetError {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = TargetCapabilityStatus.UNSUPPORTED
            return TargetError.Unsupported
        }
        if (error is ConnectionException) {
            capability = TargetCapabilityStatus.UNAVAILABLE
            return TargetError.TargetDisconnected
        }
        capability = TargetCapabilityStatus.FAILED
        return TargetError.ExecutionFailed(message.ifEmpty { "$method failed" })
    }

    private fun extractSessionId(result: Any?): String? {
        val map = result as? Map<*, *>
        return map?.get("sessionId") as? String
    }

    private fun resetState() {
        capability = null
        targetLog.clear()
        _targets.value = emptyList()
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }
}