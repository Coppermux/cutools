package com.coppermux.cutools.tools.application.engine

import com.coppermux.cutools.core.error.StorageException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.tools.application.models.StorageActionResult
import com.coppermux.cutools.tools.application.models.StorageCapabilityStatus
import com.coppermux.cutools.tools.application.models.StorageType
import com.coppermux.cutools.tools.application.models.StorageUsageModel
import com.coppermux.cutools.tools.application.models.UsageForTypeModel
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates origin-level storage quota tracking and data clearing for the
 * currently attached page target via the CDP `Storage` domain.
 *
 * Sub-Phase 6A:
 * - [getUsageAndQuota] issues `Storage.getUsageAndQuota` for an origin and
 *   parses the genuine usage/quota numbers plus the per-type usage breakdown.
 *   CDP failures are mapped to explicit [StorageActionResult] statuses
 *   (`UNSUPPORTED`, `UNAVAILABLE`, `FAILED`) — no mock or hardcoded numbers are
 *   ever returned (No-Fake-Capability rule).
 * - [clearDataForOrigin] issues `Storage.clearDataForOrigin` for the requested
 *   [StorageType]s, serialized as the CDP comma-separated `storageTypes` list.
 * - Session attach/detach lifecycle resets all target-scoped usage state, and a
 *   generation counter guards stale async results delivered after detach.
 *
 * Threading: state fields are `@Volatile`; all Chrome communication reuses the
 * shared [CommandDispatcher] — no second transport or dispatcher is created.
 */
class StorageEngine(
    private val dispatcher: CommandDispatcher,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `Storage` domain capability for the current session/target. */
    @Volatile
    var capability: StorageCapabilityStatus? = null
        private set

    /** The most recent genuine `Storage.getUsageAndQuota` result, or null. */
    @Volatile
    var latestUsage: StorageUsageModel? = null
        private set

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session and resets any prior target's usage state. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        capability = null
        latestUsage = null
    }

    /** Detaches, clearing all target-scoped usage state. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        capability = null
        latestUsage = null
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    // ================================================================
    // Usage & quota
    // ================================================================

    /**
     * Asynchronously fetches origin-level usage and quota via
     * `Storage.getUsageAndQuota`.
     *
     * On success the parsed [StorageUsageModel] is delivered and cached in
     * [latestUsage]. Protocol failures resolve to
     * [com.coppermux.cutools.tools.application.models.StorageActionStatus.UNSUPPORTED]
     * (method unavailable), [StorageActionStatus.UNAVAILABLE] (no bound
     * session), or [StorageActionStatus.FAILED] — never to fabricated numbers.
     */
    fun getUsageAndQuota(origin: String, callback: (StorageActionResult<StorageUsageModel>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = StorageCapabilityStatus.UNAVAILABLE
            callback(StorageActionResult.unavailable("Storage domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Storage.getUsageAndQuota",
            params = mapOf("origin" to origin),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    try {
                        val usage = parseUsageAndQuota(result.response.result, origin)
                        latestUsage = usage
                        capability = StorageCapabilityStatus.SUPPORTED
                        callback(StorageActionResult.success(usage))
                    } catch (e: StorageException) {
                        capability = StorageCapabilityStatus.FAILED
                        callback(StorageActionResult.failed(e.message, e))
                    }
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "Storage.getUsageAndQuota"))
                }
            }
        }
    }

    // ================================================================
    // Clearing
    // ================================================================

    /**
     * Asynchronously clears the requested [StorageType]s for an origin via
     * `Storage.clearDataForOrigin`.
     *
     * The [StorageType]s are serialized into Chrome's comma-separated
     * `storageTypes` parameter. An empty [storageTypes] list, a missing bound
     * session, an unavailable method, and protocol failures each resolve to an
     * explicit [StorageActionResult] status.
     */
    fun clearDataForOrigin(
        origin: String,
        storageTypes: List<StorageType>,
        callback: (StorageActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = StorageCapabilityStatus.UNAVAILABLE
            callback(StorageActionResult.unavailable("Storage domain is not bound to an active session"))
            return
        }
        if (storageTypes.isEmpty()) {
            callback(StorageActionResult.failed("No storage types provided to clear"))
            return
        }
        val gen = generation.get()
        val typesParam = storageTypes.joinToString(",") { it.wireValue }
        dispatcher.sendCommand(
            method = "Storage.clearDataForOrigin",
            params = mapOf(
                "origin" to origin,
                "storageTypes" to typesParam,
            ),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = StorageCapabilityStatus.SUPPORTED
                    callback(StorageActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "Storage.clearDataForOrigin"))
                }
            }
        }
    }

    // ================================================================
    // Parsing & error mapping
    // ================================================================

    /**
     * Parses a genuine `Storage.getUsageAndQuota` result into a
     * [StorageUsageModel]. Missing mandatory fields throw a [StorageException]
     * (resolved to [StorageCapabilityStatus.FAILED] by the caller) rather than
     * fabricating values.
     */
    private fun parseUsageAndQuota(result: Any?, origin: String): StorageUsageModel {
        val map = result as? Map<*, *>
            ?: throw StorageException("Storage.getUsageAndQuota result is not an object")
        val usage = (map["usage"] as? Number)?.toLong()
            ?: throw StorageException("Storage.getUsageAndQuota result missing 'usage'")
        val quota = (map["quota"] as? Number)?.toLong()
            ?: throw StorageException("Storage.getUsageAndQuota result missing 'quota'")
        val breakdown = (map["usageBreakdown"] as? List<*>)?.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            val type = StorageType.fromWire(item["storageType"] as? String)
            val bytes = (item["usage"] as? Number)?.toLong() ?: 0L
            UsageForTypeModel(storageType = type, usageBytes = bytes)
        } ?: emptyList()
        return StorageUsageModel(
            origin = origin,
            usageBytes = usage,
            quotaBytes = quota,
            usageBreakdown = breakdown,
            retrievedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Maps a CDP command failure to an explicit [StorageActionResult] status.
     * A method-not-found error (`-32601`) is [StorageActionStatus.UNSUPPORTED];
     * everything else is [StorageActionStatus.FAILED].
     */
    private fun classifyFailure(error: Throwable, method: String): StorageActionResult<Nothing> {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = StorageCapabilityStatus.UNSUPPORTED
            return StorageActionResult.unsupported("$method is not available on the attached target", error)
        }
        capability = StorageCapabilityStatus.FAILED
        return StorageActionResult.failed("$method failed: ${error.message}", error)
    }
}