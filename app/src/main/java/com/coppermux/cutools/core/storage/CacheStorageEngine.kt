package com.coppermux.cutools.core.storage

import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.error.StorageException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.storage.models.CacheEntryModel
import com.coppermux.cutools.core.storage.models.CacheStorageBucketModel
import com.coppermux.cutools.core.storage.models.StorageIntrospectionActionResult
import com.coppermux.cutools.core.storage.models.StorageIntrospectionCapabilityStatus
import com.coppermux.cutools.core.storage.models.StorageIntrospectionError
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parses genuine `CacheStorage` domain wire payloads into the typed
 * introspection models without fabricating any state. Pure and stateless so it
 * is trivially unit-testable.
 */
object CacheStorageParser {

    /** Parses the genuine `caches` list of a `requestCacheNames` result. */
    fun parseBuckets(raw: Any?): List<CacheStorageBucketModel> {
        if (raw !is Map<*, *>) return emptyList()
        val caches = raw["caches"] as? List<*> ?: return emptyList()
        return caches.mapNotNull { cache ->
            if (cache !is Map<*, *>) return@mapNotNull null
            val cacheId = cache["cacheId"] as? String ?: return@mapNotNull null
            CacheStorageBucketModel(
                cacheId = cacheId,
                securityOrigin = (cache["securityOrigin"] as? String) ?: "",
                cacheName = (cache["cacheName"] as? String) ?: "",
            )
        }
    }

    /**
     * Parses the genuine `cacheDataEntries` list (with `entries` fallback) of a
     * `requestEntries` result, mapping each entry's request URL, request header
     * names, response status/status text, and cached response header names.
     */
    fun parseEntries(raw: Any?): List<CacheEntryModel> {
        if (raw !is Map<*, *>) return emptyList()
        val entries = (raw["cacheDataEntries"] as? List<*>)
            ?: raw["entries"] as? List<*> ?: return emptyList()
        return entries.mapNotNull { entry ->
            if (entry !is Map<*, *>) return@mapNotNull null
            CacheEntryModel(
                requestURL = (entry["requestURL"] as? String) ?: "",
                requestHeaders = parseHeaderNames(entry["requestHeaders"]),
                responseStatus = (entry["responseStatus"] as? Number)?.toInt() ?: 0,
                responseStatusText = (entry["responseStatusText"] as? String) ?: "",
                responseHeaders = parseHeaderNames(entry["responseHeaders"]),
            )
        }
    }

    /** Maps the CDP response/request header array of {name,value} pairs into its names. */
    private fun parseHeaderNames(raw: Any?): List<String> {
        if (raw !is List<*>) return emptyList()
        val names = linkedSetOf<String>()
        raw.forEach { header ->
            if (header is Map<*, *>) {
                val name = header["name"] as? String ?: return@forEach
                names += name
            }
        }
        return names.toList()
    }
}

/**
 * Orchestrates browser cache storage bucket listing, cached entry inspection,
 * bucket deletion, and single entry deletion for the currently attached page
 * target via the CDP `CacheStorage` domain.
 *
 * Sub-Phase 8C — IndexedDB & CacheStorage Introspection Engine:
 * - [requestCacheNames] issues `CacheStorage.requestCacheNames` for an origin and
 *   parses the genuine cache buckets.
 * - [requestEntries] issues `CacheStorage.requestEntries` with `skipCount` /
 *   `pageSize` pagination and maps the genuine request URLs, request header
 *   names, response status, and cached response header names.
 * - [deleteCache] / [deleteEntry] issue the matching mutation commands.
 * - Session attach/detach lifecycle resets all target-scoped state; a generation
 *   counter rejects stale async results delivered after detach.
 * - Protocol failures, missing sessions, unavailable methods, and connection
 *   loss each resolve to an explicit [StorageIntrospectionActionResult.Failure]
 *   carrying a [StorageIntrospectionError] (`Unsupported` / `Unavailable` /
 *   `ExecutionFailed` / `TargetDisconnected`) — No-Fake-Capability.
 *
 * Threading: state fields are `@Volatile`; all Chrome communication reuses the
 * shared [CommandDispatcher] — no second transport or dispatcher is created.
 */
class CacheStorageEngine(
    private val dispatcher: CommandDispatcher,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `CacheStorage` domain capability for the current session/target. */
    @Volatile
    var capability: StorageIntrospectionCapabilityStatus? = null
        private set

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session and resets any prior target's state. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        capability = null
    }

    /** Detaches, clearing all target-scoped cache state. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        capability = null
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    // ================================================================
    // Inspection
    // ================================================================

    /**
     * Asynchronously lists the available cache storage buckets for [origin] via
     * `CacheStorage.requestCacheNames`. Protocol failures resolve to an explicit
     * [StorageIntrospectionActionResult.Failure] — never to fabricated buckets.
     */
    fun requestCacheNames(origin: String, callback: (StorageIntrospectionActionResult<List<CacheStorageBucketModel>>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "CacheStorage.requestCacheNames",
            params = mapOf("securityOrigin" to origin),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result
                    if (payload !is Map<*, *>) {
                        capability = StorageIntrospectionCapabilityStatus.FAILED
                        callback(StorageIntrospectionActionResult.Failure(
                            StorageIntrospectionError.ExecutionFailed(
                                StorageException("CacheStorage.requestCacheNames result is not an object")
                            )
                        ))
                        return@sendCommand
                    }
                    capability = StorageIntrospectionCapabilityStatus.SUPPORTED
                    callback(StorageIntrospectionActionResult.Success(CacheStorageParser.parseBuckets(payload)))
                }
                is CommandResult.Failure -> callback(classifyFailure(result.error, "CacheStorage.requestCacheNames"))
            }
        }
    }

    /**
     * Asynchronously queries a page of cached entries within a [cacheId] bucket
     * via `CacheStorage.requestEntries` with [skipCount] / [pageSize]
     * pagination.
     */
    fun requestEntries(
        cacheId: String,
        skipCount: Int,
        pageSize: Int,
        callback: (StorageIntrospectionActionResult<List<CacheEntryModel>>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "CacheStorage.requestEntries",
            params = mapOf(
                "cacheId" to cacheId,
                "skipCount" to skipCount,
                "pageSize" to pageSize,
            ),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result
                    if (payload !is Map<*, *>) {
                        capability = StorageIntrospectionCapabilityStatus.FAILED
                        callback(StorageIntrospectionActionResult.Failure(
                            StorageIntrospectionError.ExecutionFailed(
                                StorageException("CacheStorage.requestEntries result is not an object")
                            )
                        ))
                        return@sendCommand
                    }
                    capability = StorageIntrospectionCapabilityStatus.SUPPORTED
                    callback(StorageIntrospectionActionResult.Success(CacheStorageParser.parseEntries(payload)))
                }
                is CommandResult.Failure -> callback(classifyFailure(result.error, "CacheStorage.requestEntries"))
            }
        }
    }

    // ================================================================
    // Mutation
    // ================================================================

    /**
     * Asynchronously deletes an entire cache bucket via
     * `CacheStorage.deleteCache`.
     */
    fun deleteCache(cacheId: String, callback: (StorageIntrospectionActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "CacheStorage.deleteCache",
            params = mapOf("cacheId" to cacheId),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = StorageIntrospectionCapabilityStatus.SUPPORTED
                    callback(StorageIntrospectionActionResult.Success(Unit))
                }
                is CommandResult.Failure -> callback(classifyFailure(result.error, "CacheStorage.deleteCache"))
            }
        }
    }

    /**
     * Asynchronously deletes a single cached entry identified by [requestUrl]
     * from a [cacheId] bucket via `CacheStorage.deleteEntry`.
     */
    fun deleteEntry(cacheId: String, requestUrl: String, callback: (StorageIntrospectionActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "CacheStorage.deleteEntry",
            params = mapOf("cacheId" to cacheId, "request" to requestUrl),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = StorageIntrospectionCapabilityStatus.SUPPORTED
                    callback(StorageIntrospectionActionResult.Success(Unit))
                }
                is CommandResult.Failure -> callback(classifyFailure(result.error, "CacheStorage.deleteEntry"))
            }
        }
    }

    // ================================================================
    // Error mapping & state helpers
    // ================================================================

    private fun unavailable(): StorageIntrospectionActionResult<Nothing> {
        capability = StorageIntrospectionCapabilityStatus.UNAVAILABLE
        return StorageIntrospectionActionResult.Failure(
            StorageIntrospectionError.Unavailable("CacheStorage domain is not bound to an active session")
        )
    }

    /**
     * Maps a CDP command failure to an explicit [StorageIntrospectionError]. A
     * method-not-found error (`-32601`) is [StorageIntrospectionError.Unsupported];
     * a connection failure is [StorageIntrospectionError.TargetDisconnected];
     * everything else is [StorageIntrospectionError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): StorageIntrospectionActionResult<Nothing> {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = StorageIntrospectionCapabilityStatus.UNSUPPORTED
            return StorageIntrospectionActionResult.Failure(
                StorageIntrospectionError.Unsupported("$method is not available on the attached target")
            )
        }
        if (error is ConnectionException) {
            capability = StorageIntrospectionCapabilityStatus.UNAVAILABLE
            return StorageIntrospectionActionResult.Failure(StorageIntrospectionError.TargetDisconnected())
        }
        capability = StorageIntrospectionCapabilityStatus.FAILED
        return StorageIntrospectionActionResult.Failure(StorageIntrospectionError.ExecutionFailed(StorageException("$method failed: ${error.message}", cause = error)))
    }
}