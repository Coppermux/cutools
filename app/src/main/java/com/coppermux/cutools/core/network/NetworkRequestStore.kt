package com.coppermux.cutools.core.network

import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Thread-safe, in-memory store of [NetworkRequestModel]s keyed by Chrome's
 * [NetworkRequestModel.requestId].
 *
 * All mutations are serialized under an internal lock, so event handlers from
 * the CDP callback thread never race each other. Reads return immutable
 * snapshots, so callers cannot observe a partially-mutated map.
 *
 * Phase 5E — bounded memory & circular buffer:
 *  - [config] enforces strict caps on the number of retained requests and the
 *    approximate total retained bytes (URLs, request/response headers, bodies,
 *    and per-entry overhead).
 *  - When a cap is exceeded, the oldest entries are evicted (oldest-first
 *    circular buffer), so memory stays bounded under sustained high-frequency
 *    traffic and cannot grow without bound.
 *  - [retainedBytes] and [evictions] expose the cumulative footprint and how
 *    many entries have been automatically evicted.
 */
class NetworkRequestStore(
    private val config: NetworkStoreConfig = NetworkStoreConfig(),
) {

    private val lock = ReentrantLock()

    /** Insertion-ordered map: oldest entries are evicted first. */
    private val byId = LinkedHashMap<String, NetworkRequestModel>()

    /** Approximate retained bytes per requestId (drives memory bounding). */
    private val byteSizes = HashMap<String, Long>()

    /** Sum of [byteSizes]; the approximate current memory footprint. */
    private var totalBytes = 0L

    /** Number of requests automatically evicted since construction/clear. */
    private var evictionCount = 0L

    /** The configured request-count cap. */
    val maxRequests: Long get() = config.maxRequests

    /** The configured approximate byte cap. */
    val maxBufferBytes: Long get() = config.maxBufferBytes

    /** The current approximate retained byte count. */
    val retainedBytes: Long get() = locked { totalBytes }

    /** Number of entries automatically evicted due to cap pressure. */
    val evictions: Long get() = locked { evictionCount }

    /** The current model for [requestId], or null. */
    fun get(requestId: String): NetworkRequestModel? = locked { byId[requestId] }

    /** Replaces the model for [requestId]. */
    fun put(model: NetworkRequestModel) {
        locked { upsertLocked(model.requestId, { model }, { model }) }
    }

    /**
     * Atomically inserts or transforms the model for [requestId].
     *
     * When no model exists, [create] produces the initial entry. When one
     * exists, [transform] receives the current model and returns the next one
     * (the returned entry is written back atomically). The transformed entry is
     * returned for callers that need it. Inserting a brand-new request appends
     * it as the newest; updating an existing request keeps its original order.
     */
    fun upsert(
        requestId: String,
        create: () -> NetworkRequestModel,
        transform: (NetworkRequestModel) -> NetworkRequestModel,
    ): NetworkRequestModel = locked { upsertLocked(requestId, create, transform) }

    private fun upsertLocked(
        requestId: String,
        create: () -> NetworkRequestModel,
        transform: (NetworkRequestModel) -> NetworkRequestModel,
    ): NetworkRequestModel {
        val existing = byId[requestId]
        val next = if (existing == null) create() else transform(existing)
        val oldBytes = byteSizes[requestId] ?: 0L
        val newBytes = estimateBytes(next)
        if (existing == null) {
            byId[requestId] = next
            byteSizes[requestId] = newBytes
            totalBytes += newBytes
        } else {
            byId[requestId] = next
            byteSizes[requestId] = newBytes
            totalBytes += newBytes - oldBytes
        }
        evictLocked()
        // A lone entry exceeding the byte cap is retained (its only record must
        // not be discarded), but the newest inserted entry is returned.
        return byId[requestId] ?: next
    }

    /**
     * Evicts the oldest entries (oldest-first circular buffer) until both caps
     * are satisfied. A single entry larger than [maxBufferBytes] is retained —
     * discarding the only record of a request would lose it entirely.
     */
    private fun evictLocked() {
        val keys = byId.keys.iterator()
        while (true) {
            val overCount = byId.size > maxRequests
            val overBytes = byId.size > 1 && totalBytes > maxBufferBytes
            if (!overCount && !overBytes) return
            if (!keys.hasNext()) return
            val oldestId = keys.next()
            keys.remove()
            totalBytes -= byteSizes.remove(oldestId) ?: 0L
            evictionCount++
        }
    }

    /** Number of tracked requests. */
    fun size(): Int = locked { byId.size }

    /** Removes all tracked requests and resets the footprint counters. */
    fun clear() {
        locked {
            byId.clear()
            byteSizes.clear()
            totalBytes = 0L
            evictionCount = 0L
        }
    }

    /**
     * Immutable snapshot of all tracked requests. Order is deterministic
     * (sorted by requestId) so tests and UI are stable.
     */
    fun snapshot(): List<NetworkRequestModel> = locked {
        byId.values.toList().sortedBy { it.requestId }
    }

    /**
     * Approximate cost of a request: the UTF-8 byte length of every string
     * field and header value, plus a fixed per-entry overhead. Used only to
     * drive memory bounding — never reported as a Chrome metric.
     */
    private fun estimateBytes(model: NetworkRequestModel): Long {
        var bytes = 128L
        fun accept(value: String?) {
            if (value != null) bytes += value.toByteArray(StandardCharsets.UTF_8).size.toLong()
        }
        fun acceptAll(values: Map<String, String>) {
            values.forEach { (k, v) -> { accept(k); accept(v) } }
        }
        accept(model.requestId)
        accept(model.url)
        accept(model.method)
        accept(model.resourceType)
        accept(model.initiatorType)
        accept(model.postData)
        accept(model.responseStatusText)
        accept(model.responseMimeType)
        accept(model.loadingError)
        accept(model.sessionId)
        accept(model.targetId)
        accept(model.timestamp?.toString())
        accept(model.responseStatus?.toString())
        acceptAll(model.requestHeaders)
        acceptAll(model.responseHeaders)
        return bytes
    }

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}