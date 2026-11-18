package com.coppermux.cutools.core.accessibility

import com.coppermux.cutools.core.accessibility.models.AXTreeModel
import com.coppermux.cutools.core.accessibility.models.AccessibilityActionResult
import com.coppermux.cutools.core.accessibility.models.AccessibilityCapabilityStatus
import com.coppermux.cutools.core.accessibility.models.AccessibilityError
import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates accessibility tree inspection for the currently attached page
 * target via the CDP `Accessibility` domain.
 *
 * Phase 9A — Accessibility Domain Engine & Tree Model:
 * - [enable] / [disable] manage `Accessibility` domain notifications.
 * - [getFullAXTree] retrieves the full accessibility tree, optionally bounded by
 *   `depth` / `max_depth` and scoped to a `frameId`.
 * - [getPartialAXTree] retrieves a partial tree from a `nodeId` / `backendNodeId`
 *   / `objectId`, optionally fetching relatives (`fetchRelatives`).
 * - The pure [AccessibilityParser] ingests genuine `nodes` arrays into
 *   [AXTreeModel] — never fabricating role, name, description, or property
 *   state (No-Fake-Capability rule).
 * - Session attach/detach lifecycle resets all target-scoped state; a
 *   generation counter rejects stale async results delivered after detach /
 *   re-bind.
 * - Protocol failures, missing sessions, unavailable methods, and connection
 *   loss each resolve to an explicit [AccessibilityActionResult.Failure]
 *   carrying an [AccessibilityError] (`Unsupported` / `Unavailable` /
 *   `ExecutionFailed` / `TargetDisconnected`).
 *
 * Threading: all shared state is `@Volatile`; the generation counter is atomic
 * so concurrent lifecycles never accept stale results. All Chrome
 * communication reuses the shared [CommandDispatcher] — no second transport or
 * dispatcher is created.
 */
class AccessibilityEngine(
    private val dispatcher: CommandDispatcher,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `Accessibility` domain capability for the current session/target. */
    @Volatile
    var capability: AccessibilityCapabilityStatus? = null
        private set

    /** Whether `Accessibility` domain notifications are currently enabled. */
    @Volatile
    var enabled: Boolean = false
        private set

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session, resets prior target state, and enables the domain. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        resetState()
        sendEnable()
    }

    /** Detaches, clearing all target-scoped state. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        resetState()
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    /** Releases no event subscriptions (the domain is command-driven, not event-driven). */
    fun shutdown() {
        // no-op: the Accessibility domain has no subscription state to release
    }

    // ================================================================
    // Domain lifecycle
    // ================================================================

    /**
     * Enables `Accessibility` domain notifications via `Accessibility.enable`.
     * Protocol failures resolve to an explicit [AccessibilityActionResult.Failure].
     */
    fun enable(callback: (AccessibilityActionResult<Unit>) -> Unit = {}) {
        sendEnable(callback)
    }

    /**
     * Disables `Accessibility` domain notifications via `Accessibility.disable`.
     */
    fun disable(callback: (AccessibilityActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = AccessibilityCapabilityStatus.UNAVAILABLE
            callback(AccessibilityActionResult.Failure(AccessibilityError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Accessibility.disable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    enabled = false
                    callback(AccessibilityActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(AccessibilityActionResult.Failure(classifyFailure(result.error, "Accessibility.disable")))
                }
            }
        }
    }

    /**
     * Retrieves the full accessibility tree via `Accessibility.getFullAXTree`.
     * The optional [depth] / [maxDepth] bounds traversal depth and [frameId]
     * scopes to a frame; only genuinely-supplied parameters are serialized.
     * The parsed nodes are delivered as an [AccessibilityActionResult.Success].
     */
    fun getFullAXTree(
        depth: Int? = null,
        maxDepth: Int? = null,
        frameId: String? = null,
        callback: (AccessibilityActionResult<AXTreeModel>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = AccessibilityCapabilityStatus.UNAVAILABLE
            callback(AccessibilityActionResult.Failure(AccessibilityError.Unavailable))
            return
        }
        val params = buildPair("depth" to depth, "max_depth" to maxDepth, "frameId" to frameId)
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Accessibility.getFullAXTree",
            params = params,
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    callback(AccessibilityActionResult.Success(parseTree(result.response.result)))
                }
                is CommandResult.Failure -> {
                    callback(AccessibilityActionResult.Failure(classifyFailure(result.error, "Accessibility.getFullAXTree")))
                }
            }
        }
    }

    /**
     * Retrieves a partial accessibility tree via `Accessibility.getPartialAXTree`,
     * starting from a `nodeId` / `backendNodeId` / `objectId` and optionally
     * fetching relatives with [fetchRelatives]. Only genuinely-supplied
     * parameters are serialized.
     */
    fun getPartialAXTree(
        nodeId: Int? = null,
        backendNodeId: Int? = null,
        objectId: String? = null,
        fetchRelatives: Boolean? = null,
        callback: (AccessibilityActionResult<AXTreeModel>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = AccessibilityCapabilityStatus.UNAVAILABLE
            callback(AccessibilityActionResult.Failure(AccessibilityError.Unavailable))
            return
        }
        val params = buildPair(
            "nodeId" to nodeId,
            "backendNodeId" to backendNodeId,
            "objectId" to objectId,
            "fetchRelatives" to fetchRelatives,
        )
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Accessibility.getPartialAXTree",
            params = params,
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    callback(AccessibilityActionResult.Success(parseTree(result.response.result)))
                }
                is CommandResult.Failure -> {
                    callback(AccessibilityActionResult.Failure(classifyFailure(result.error, "Accessibility.getPartialAXTree")))
                }
            }
        }
    }

    private fun sendEnable(callback: (AccessibilityActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = AccessibilityCapabilityStatus.UNAVAILABLE
            callback(AccessibilityActionResult.Failure(AccessibilityError.Unavailable))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Accessibility.enable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = AccessibilityCapabilityStatus.SUPPORTED
                    enabled = true
                    callback(AccessibilityActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(AccessibilityActionResult.Failure(classifyFailure(result.error, "Accessibility.enable")))
                }
            }
        }
    }

    private fun parseTree(raw: Any?): AXTreeModel =
        AccessibilityParser.parseTree(raw) ?: AXTreeModel(emptyList(), null)

    // ================================================================
    // Error mapping & state helpers
    // ================================================================

    /**
     * Maps a CDP command failure to an explicit [AccessibilityError]. A
     * method-not-found error (`-32601`) is [AccessibilityError.Unsupported]; a
     * connection failure is [AccessibilityError.TargetDisconnected]; everything
     * else is [AccessibilityError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): AccessibilityError {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = AccessibilityCapabilityStatus.UNSUPPORTED
            return AccessibilityError.Unsupported
        }
        if (error is ConnectionException) {
            capability = AccessibilityCapabilityStatus.UNAVAILABLE
            return AccessibilityError.TargetDisconnected
        }
        capability = AccessibilityCapabilityStatus.FAILED
        return AccessibilityError.ExecutionFailed(message.ifEmpty { "Unknown accessibility error" })
    }

    private fun buildPair(vararg values: Pair<String, Any?>): Map<String, Any>? {
        val params = mutableMapOf<String, Any>()
        values.forEach { (key, value) -> if (value != null) params[key] = value }
        return if (params.isEmpty()) null else params
    }

    private fun resetState() {
        capability = null
        enabled = false
    }
}