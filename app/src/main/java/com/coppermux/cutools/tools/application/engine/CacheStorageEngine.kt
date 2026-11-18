package com.coppermux.cutools.tools.application.engine

import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.tools.application.models.CacheActionResult
import com.coppermux.cutools.tools.application.models.CacheActionStatus
import com.coppermux.cutools.tools.application.models.CacheEntryModel
import com.coppermux.cutools.tools.application.models.CacheModel
import com.coppermux.cutools.tools.application.models.CacheStorageCapabilityStatus
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates browser cache storage bucket listing, cached entry inspection,
 * bucket deletion, and single entry deletion for the currently attached page
 * target via the CDP `CacheStorage` domain.
 *
 * Sub-Phase 6D:
 * - [getCacheNames] issues `CacheStorage.requestCacheNames` for an origin and
 *   parses the genuine cache buckets.
 * - [getCacheEntries] issues `CacheStorage.requestEntries` with `skipCount` /
 *   `pageSize` pagination and maps the genuine request URLs, request methods,
 *   and cached response header names.
 * - [deleteCache] / [deleteCacheEntry] issue the matching mutation commands.
 *   CDP failures are mapped to explicit [CacheActionResult] statuses
 *   (`UNSUPPORTED`, `UNAVAILABLE`, `FAILED`) — no mock or hardcoded cache
 *   values are ever returned (No-Fake-Capability).
 * - Session attach/detach lifecycle resets all target-scoped state, and a
 *   generation counter guards stale async results delivered after detach.
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
    var capability: CacheStorageCapabilityStatus? = null
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
     * `CacheStorage.requestCacheNames`.
     *
     * Protocol failures resolve to an explicit [CacheActionResult] status —
     * never to fabricated cache buckets.
     */
    fun getCacheNames(origin: String, callback: (CacheActionResult<List<CacheModel>>) -> Unit) {
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
                        capability = CacheStorageCapabilityStatus.FAILED
                        callback(CacheActionResult.failed("CacheStorage.requestCacheNames result is not an object"))
                        return@sendCommand
                    }
                    capability = CacheStorageCapabilityStatus.SUPPORTED
                    callback(CacheActionResult.success(parseBuckets(payload)))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "CacheStorage.requestCacheNames"))
                }
            }
        }
    }

    /**
     * Asynchronously queries the cached entries within a [cacheId] bucket via
     * `CacheStorage.requestEntries` with [skip] / [limit] pagination.
     */
    fun getCacheEntries(
        cacheId: String,
        skip: Int,
        limit: Int,
        callback: (CacheActionResult<List<CacheEntryModel>>) -> Unit,
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
                "skipCount" to skip,
                "pageSize" to limit,
            ),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result
                    if (payload !is Map<*, *>) {
                        capability = CacheStorageCapabilityStatus.FAILED
                        callback(CacheActionResult.failed("CacheStorage.requestEntries result is not an object"))
                        return@sendCommand
                    }
                    capability = CacheStorageCapabilityStatus.SUPPORTED
                    callback(CacheActionResult.success(parseEntries(payload)))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "CacheStorage.requestEntries"))
                }
            }
        }
    }

    // ================================================================
    // Mutation
    // ================================================================

    /**
     * Asynchronously deletes an entire cache bucket via `CacheStorage.deleteCache`.
     */
    fun deleteCache(cacheId: String, callback: (CacheActionResult<Unit>) -> Unit) {
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
                    capability = CacheStorageCapabilityStatus.SUPPORTED
                    callback(CacheActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "CacheStorage.deleteCache"))
                }
            }
        }
    }

    /**
     * Asynchronously deletes a single cached entry by [requestUrl] from a
     * [cacheId] bucket via `CacheStorage.deleteEntry`.
     */
    fun deleteCacheEntry(cacheId: String, requestUrl: String, callback: (CacheActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "CacheStorage.deleteEntry",
            params = mapOf("cacheId" to cacheId, "requestUrl" to requestUrl),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = CacheStorageCapabilityStatus.SUPPORTED
                    callback(CacheActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "CacheStorage.deleteEntry"))
                }
            }
        }
    }

    // ================================================================
    // Parsing & error mapping
    // ================================================================

    private fun unavailable(): CacheActionResult<Nothing> {
        capability = CacheStorageCapabilityStatus.UNAVAILABLE
        return CacheActionResult.unavailable("CacheStorage domain is not bound to an active session")
    }

    /** Parses the genuine `caches` list of a `requestCacheNames` result. */
    private fun parseBuckets(result: Map<*, *>): List<CacheModel> {
        val caches = result["caches"] as? List<*> ?: return emptyList()
        return caches.mapNotNull { cache ->
            if (cache !is Map<*, *>) return@mapNotNull null
            val cacheId = cache["cacheId"] as? String ?: return@mapNotNull null
            CacheModel(
                cacheId = cacheId,
                securityOrigin = (cache["origin"] as? String) ?: "",
                cacheName = (cache["cacheName"] as? String) ?: "",
            )
        }
    }

    /**
     * Parses the genuine `entries` list of a `requestEntries` result, mapping
     * each entry's request URL, request method, and cached response header names.
     */
    private fun parseEntries(result: Map<*, *>): List<CacheEntryModel> {
        val entries = result["entries"] as? List<*> ?: return emptyList()
        return entries.mapNotNull { entry ->
            if (entry !is Map<*, *>) return@mapNotNull null
            CacheEntryModel(
                requestUrl = (entry["requestURL"] as? String) ?: (entry["requestUrl"] as? String) ?: "",
                responseHeaderNames = parseHeaderNames(entry["responseHeaders"] as? List<*>),
                requestMethod = (entry["requestMethod"] as? String) ?: "",
            )
        }
    }

    /** Maps the CDP response header array of {name,value} pairs into its names. */
    private fun parseHeaderNames(headers: List<*>?): List<String> {
        if (headers == null) return emptyList()
        val names = linkedSetOf<String>()
        headers.forEach { header ->
            if (header is Map<*, *>) {
                val name = header["name"] as? String ?: return@forEach
                names += name
            }
        }
        return names.toList()
    }

    /**
     * Maps a CDP command failure to an explicit [CacheActionResult] status.
     * A method-not-found error (`-32601`) is [CacheActionStatus.UNSUPPORTED];
     * everything else is [CacheActionStatus.FAILED].
     */
    private fun classifyFailure(error: Throwable, method: String): CacheActionResult<Nothing> {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = CacheStorageCapabilityStatus.UNSUPPORTED
            return CacheActionResult.unsupported("$method is not available on the attached target", error)
        }
        capability = CacheStorageCapabilityStatus.FAILED
        return CacheActionResult.failed("$method failed: ${error.message}", error)
    }
}