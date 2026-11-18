package com.coppermux.cutools.core.storage

import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.error.StorageException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.storage.models.CacheStorageContentModel
import com.coppermux.cutools.core.storage.models.CacheStorageContentUpdatedModel
import com.coppermux.cutools.core.storage.models.CacheStorageKeyModel
import com.coppermux.cutools.core.storage.models.CacheStorageListUpdatedModel
import com.coppermux.cutools.core.storage.models.StorageActionResult
import com.coppermux.cutools.core.storage.models.StorageCapabilityStatus
import com.coppermux.cutools.core.storage.models.StorageError
import com.coppermux.cutools.core.storage.models.StorageTypeModel
import com.coppermux.cutools.core.storage.models.StorageUsageAndQuotaModel
import com.coppermux.cutools.core.storage.models.UsageForTypeModel
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parses genuine `Storage` domain wire payloads into the typed storage models
 * without fabricating any state. Pure and stateless so it is trivially
 * unit-testable.
 */
object StorageParser {

    /**
     * Parses a genuine `Storage.getUsageAndQuota` result into a
     * [StorageUsageAndQuotaModel]. Returns null when [raw] is not a map or is
     * missing a mandatory `usage` / `quota` field — a malformed payload is never
     * interpreted as fabricated numbers (No-Fake-Capability rule).
     */
    fun parseUsageAndQuota(raw: Any?): StorageUsageAndQuotaModel? {
        if (raw !is Map<*, *>) return null
        val usage = (raw["usage"] as? Number)?.toLong() ?: return null
        val quota = (raw["quota"] as? Number)?.toLong() ?: return null
        return StorageUsageAndQuotaModel(
            usage = usage,
            quota = quota,
            usageBreakdown = parseUsageBreakdown(raw["usageBreakdown"]),
        )
    }

    /**
     * Parses a genuine `Storage.cacheStorageContentUpdated` event params map into
     * a [CacheStorageContentUpdatedModel]. Returns null when [raw] is not a map
     * or carries no genuine origin signal.
     */
    fun parseCacheContentUpdated(raw: Any?): CacheStorageContentUpdatedModel? {
        if (raw !is Map<*, *>) return null
        val origin = (raw["origin"] as? String) ?: ""
        if (origin.isEmpty()) return null
        return CacheStorageContentUpdatedModel(
            origin = origin,
            key = parseKey(raw["key"]),
            content = parseContent(raw["entry"]),
        )
    }

    /**
     * Parses a genuine `Storage.cacheStorageListUpdated` event params map into a
     * [CacheStorageListUpdatedModel]. Returns null when [raw] is not a map or
     * carries no genuine origin signal.
     */
    fun parseCacheListUpdated(raw: Any?): CacheStorageListUpdatedModel? {
        if (raw !is Map<*, *>) return null
        val origin = (raw["origin"] as? String) ?: ""
        if (origin.isEmpty()) return null
        return CacheStorageListUpdatedModel(origin = origin)
    }

    private fun parseUsageBreakdown(raw: Any?): List<UsageForTypeModel> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            UsageForTypeModel(
                storageType = StorageTypeModel.fromWire(item["storageType"] as? String),
                usageBytes = (item["usage"] as? Number)?.toLong() ?: 0L,
            )
        }
    }

    private fun parseKey(raw: Any?): CacheStorageKeyModel {
        if (raw !is Map<*, *>) return CacheStorageKeyModel()
        return CacheStorageKeyModel(
            id = (raw["id"] as? String) ?: "",
            storage = (raw["storage"] as? String) ?: "",
        )
    }

    private fun parseContent(raw: Any?): CacheStorageContentModel? {
        if (raw !is Map<*, *>) return null
        val request = raw["request"] as? Map<*, *> ?: return null
        val response = raw["response"] as? Map<*, *>
        return CacheStorageContentModel(
            requestUrl = (request["url"] as? String) ?: "",
            requestMethod = (request["method"] as? String) ?: "",
            responseHeaderNames = parseHeaderNames(response?.get("headers")),
        )
    }

    private fun parseHeaderNames(raw: Any?): List<String> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { header ->
            if (header !is Map<*, *>) return@mapNotNull null
            header["name"] as? String
        }
    }
}

/**
 * Orchestrates origin-level storage quota tracking, data clearing, and origin
 * cache tracking for the currently attached page target via the CDP `Storage`
 * domain.
 *
 * Sub-Phase 8B — Storage Quota & Origin Data Engine:
 * - [getUsageAndQuota] issues `Storage.getUsageAndQuota` for an origin and
 *   parses the genuine usage/quota numbers plus the per-type usage breakdown.
 * - [clearDataForOrigin] issues `Storage.clearDataForOrigin` for the requested
 *   [StorageTypeModel]s, serialized as the CDP comma-separated `storageTypes`
 *   list.
 * - [trackCacheStorageForOrigin] / [untrackCacheStorageForOrigin] manage
 *   per-origin cache tracking via `Storage.trackCacheStorageForOrigin` /
 *   `Storage.untrackCacheStorageForOrigin`.
 * - The engine registers `Storage.cacheStorageContentUpdated` and
 *   `Storage.cacheStorageListUpdated` listeners, ingesting genuine origin cache
 *   content/list updates for the tracked origins — never fabricating them
 *   (No-Fake-Capability rule).
 * - Session attach/detach lifecycle resets all target-scoped storage state and
 *   re-binds event subscriptions; a generation counter rejects stale async
 *   results/events delivered after detach.
 * - Protocol failures, missing sessions, unavailable methods, and connection
 *   loss each resolve to an explicit [StorageActionResult.Failure] carrying a
 *   [StorageError] (`Unsupported` / `Unavailable` / `ExecutionFailed` /
 *   `TargetDisconnected`).
 *
 * Threading: all shared state is `@Volatile`; the tracked-origin set is
 * thread-safe and the cache event logs are [CopyOnWriteArrayList]s so
 * concurrent event streams never lose an update. All Chrome communication
 * reuses the shared [CommandDispatcher] — no second transport or dispatcher is
 * created.
 */
class StorageEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
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
    var latestUsage: StorageUsageAndQuotaModel? = null
        private set

    /** The origins currently being tracked for cache storage updates. */
    @Volatile
    var trackedOrigins: Set<String> = ConcurrentHashMap.newKeySet()
        private set

    /** Genuine `Storage.cacheStorageContentUpdated` events, in observation order. */
    @Volatile
    var cacheStorageContentUpdates: List<CacheStorageContentUpdatedModel> = emptyList()
        private set

    /** Genuine `Storage.cacheStorageListUpdated` events, in observation order. */
    @Volatile
    var cacheStorageListUpdates: List<CacheStorageListUpdatedModel> = emptyList()
        private set

    /** Guards stale async results/events after detach / re-bind. */
    private val generation = AtomicInteger(0)

    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    private val trackedOriginSet = ConcurrentHashMap.newKeySet<String>()

    private val contentUpdateLog = CopyOnWriteArrayList<CacheStorageContentUpdatedModel>()

    private val listUpdateLog = CopyOnWriteArrayList<CacheStorageListUpdatedModel>()

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session, resets prior target state, and enables the domain. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        resetState()
        clearSubscriptions()
        registerSubscriptions()
    }

    /** Detaches, clearing all target-scoped storage state and subscriptions. */
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
    // Usage & quota
    // ================================================================

    /**
     * Asynchronously fetches origin-level usage and quota via
     * `Storage.getUsageAndQuota`.
     *
     * On success the parsed [StorageUsageAndQuotaModel] is delivered and cached
     * in [latestUsage]. A missing session, an unavailable method, a malformed
     * result, and protocol failures each resolve to an explicit
     * [StorageActionResult.Failure].
     */
    fun getUsageAndQuota(origin: String, callback: (StorageActionResult<StorageUsageAndQuotaModel>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = StorageCapabilityStatus.UNAVAILABLE
            callback(StorageActionResult.Failure(
                StorageError.Unavailable("Storage domain is not bound to an active session")
            ))
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
                    val usage = StorageParser.parseUsageAndQuota(result.response.result)
                    if (usage == null) {
                        capability = StorageCapabilityStatus.FAILED
                        callback(StorageActionResult.Failure(StorageError.ExecutionFailed(
                            StorageException("Storage.getUsageAndQuota result missing 'usage' or 'quota'")
                        )))
                        return@sendCommand
                    }
                    latestUsage = usage
                    capability = StorageCapabilityStatus.SUPPORTED
                    callback(StorageActionResult.Success(usage))
                }
                is CommandResult.Failure -> {
                    callback(StorageActionResult.Failure(classifyFailure(result.error, "Storage.getUsageAndQuota")))
                }
            }
        }
    }

    // ================================================================
    // Data clearance
    // ================================================================

    /**
     * Asynchronously clears the requested [StorageTypeModel]s for an origin via
     * `Storage.clearDataForOrigin`.
     *
     * The [StorageTypeModel]s are serialized into Chrome's comma-separated
     * `storageTypes` parameter. An empty [storageTypes] list, a missing bound
     * session, an unavailable method, and protocol failures each resolve to an
     * explicit [StorageActionResult.Failure].
     */
    fun clearDataForOrigin(
        origin: String,
        storageTypes: List<StorageTypeModel>,
        callback: (StorageActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = StorageCapabilityStatus.UNAVAILABLE
            callback(StorageActionResult.Failure(
                StorageError.Unavailable("Storage domain is not bound to an active session")
            ))
            return
        }
        if (storageTypes.isEmpty()) {
            capability = StorageCapabilityStatus.FAILED
            callback(StorageActionResult.Failure(
                StorageError.ExecutionFailed(StorageException("No storage types provided to clear"))
            ))
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
                    callback(StorageActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(StorageActionResult.Failure(classifyFailure(result.error, "Storage.clearDataForOrigin")))
                }
            }
        }
    }

    // ================================================================
    // Origin cache tracking
    // ================================================================

    /**
     * Asynchronously begins tracking an origin's cache storage via
     * `Storage.trackCacheStorageForOrigin`.
     *
     * On success the origin is added to [trackedOrigins]. A missing bound
     * session, an unavailable method, and protocol failures each resolve to an
     * explicit [StorageActionResult.Failure].
     */
    fun trackCacheStorageForOrigin(
        origin: String,
        scriptURL: String,
        callback: (StorageActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = StorageCapabilityStatus.UNAVAILABLE
            callback(StorageActionResult.Failure(
                StorageError.Unavailable("Storage domain is not bound to an active session")
            ))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Storage.trackCacheStorageForOrigin",
            params = mapOf(
                "origin" to origin,
                "scriptURL" to scriptURL,
            ),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    trackedOriginSet.add(origin)
                    trackedOrigins = trackedOriginSet
                    capability = StorageCapabilityStatus.SUPPORTED
                    callback(StorageActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(StorageActionResult.Failure(classifyFailure(result.error, "Storage.trackCacheStorageForOrigin")))
                }
            }
        }
    }

    /**
     * Asynchronously stops tracking an origin's cache storage via
     * `Storage.untrackCacheStorageForOrigin`.
     *
     * On success the origin is removed from [trackedOrigins]; a missing bound
     * session, an unavailable method, and protocol failures each resolve to an
     * explicit [StorageActionResult.Failure].
     */
    fun untrackCacheStorageForOrigin(origin: String, callback: (StorageActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = StorageCapabilityStatus.UNAVAILABLE
            callback(StorageActionResult.Failure(
                StorageError.Unavailable("Storage domain is not bound to an active session")
            ))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "Storage.untrackCacheStorageForOrigin",
            params = mapOf("origin" to origin),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    trackedOriginSet.remove(origin)
                    trackedOrigins = trackedOriginSet.toSet()
                    capability = StorageCapabilityStatus.SUPPORTED
                    callback(StorageActionResult.Success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(StorageActionResult.Failure(classifyFailure(result.error, "Storage.untrackCacheStorageForOrigin")))
                }
            }
        }
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("Storage.cacheStorageContentUpdated") { event -> onCacheStorageContentUpdated(event) }
        subscriptions += eventRouter.subscribe("Storage.cacheStorageListUpdated") { event -> onCacheStorageListUpdated(event) }
    }

    /** Applies a `Storage.cacheStorageContentUpdated` event if it belongs to the active session. */
    private fun onCacheStorageContentUpdated(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        val update = StorageParser.parseCacheContentUpdated(event.params) ?: return
        contentUpdateLog.add(update)
        cacheStorageContentUpdates = contentUpdateLog.toList()
    }

    /** Applies a `Storage.cacheStorageListUpdated` event if it belongs to the active session. */
    private fun onCacheStorageListUpdated(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        val update = StorageParser.parseCacheListUpdated(event.params) ?: return
        listUpdateLog.add(update)
        cacheStorageListUpdates = listUpdateLog.toList()
    }

    // ================================================================
    // Error mapping & state helpers
    // ================================================================

    /**
     * Maps a CDP command failure to an explicit [StorageError]. A
     * method-not-found error (`-32601`) is [StorageError.Unsupported]; a
     * connection failure is [StorageError.TargetDisconnected]; everything else
     * is [StorageError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): StorageError {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = StorageCapabilityStatus.UNSUPPORTED
            return StorageError.Unsupported("$method is not available on the attached target")
        }
        if (error is ConnectionException) {
            capability = StorageCapabilityStatus.UNAVAILABLE
            return StorageError.TargetDisconnected()
        }
        capability = StorageCapabilityStatus.FAILED
        return StorageError.ExecutionFailed(error)
    }

    private fun resetState() {
        capability = null
        latestUsage = null
        trackedOriginSet.clear()
        trackedOrigins = emptySet()
        contentUpdateLog.clear()
        listUpdateLog.clear()
        cacheStorageContentUpdates = emptyList()
        cacheStorageListUpdates = emptyList()
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }
}