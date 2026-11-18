package com.coppermux.cutools.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5E unit tests for the memory-bounded [NetworkRequestStore]: strict
 * configurable caps on request count and retained bytes, automatic oldest-first
 * eviction (a circular buffer), eviction of body buffers, and footprint
 * observability. These tests prove high-frequency traffic cannot grow the
 * in-memory network log without bound (no OOM).
 */
class NetworkRequestStoreMemoryTest {

    private fun model(requestId: String, postBody: String = ""): NetworkRequestModel =
        NetworkRequestModel(
            requestId = requestId,
            stage = NetworkRequestStage.PENDING,
            url = "https://example.com/$requestId",
            postData = if (postBody.isEmpty()) null else postBody,
            sessionId = "session-1",
            targetId = "target-1",
        )

    // ============ default config ============

    @Test
    fun defaultConfig_documentsCaps() {
        val store = NetworkRequestStore()
        assertEquals(500L, store.maxRequests)
        assertEquals(50L * 1024 * 1024, store.maxBufferBytes)
    }

    // ============ request-count cap ============

    @Test
    fun countCap_evictsOldestWhenExceeded() {
        val store = NetworkRequestStore(NetworkStoreConfig(maxRequests = 3, maxBufferBytes = Long.MAX_VALUE))
        store.put(model("r1"))
        store.put(model("r2"))
        store.put(model("r3"))
        assertEquals(3, store.size())

        store.put(model("r4"))
        assertEquals(3, store.size())
        assertTrue("oldest r1 must be evicted", store.get("r1") == null)
        assertEquals(listOf("r2", "r3", "r4"), store.snapshot().map { it.requestId })
        assertEquals(1L, store.evictions)
    }

    @Test
    fun countCap_evictsOldestFirstFifo() {
        val store = NetworkRequestStore(NetworkStoreConfig(maxRequests = 2, maxBufferBytes = Long.MAX_VALUE))
        for (i in 1..5) store.put(model("r$i"))
        assertEquals(2, store.size())
        assertEquals(listOf("r4", "r5"), store.snapshot().map { it.requestId })
        assertEquals(3L, store.evictions)
    }

    // ============ byte cap ============

    @Test
    fun byteCap_keepsRetainedBytesWithinCap() {
        // Each entry is small (~250 bytes); a 5000-byte cap holds ~20 of them.
        val store = NetworkRequestStore(NetworkStoreConfig(maxRequests = 100, maxBufferBytes = 5000))
        for (i in 1..40) store.put(model("r$i", postBody = "BODY".repeat(30)))
        assertTrue("retained bytes must stay at or under the cap", store.retainedBytes <= 5000)
        assertTrue(store.size() < 40)
        assertTrue("oldest must be evicted first", store.get("r1") == null)
        assertTrue(store.evictions >= 1)
    }

    @Test
    fun byteCap_countsBodyBuffersTowardsTheLimit() {
        // r1's large post body alone (~1000 bytes) exceeds the cap, so inserting
        // a second small entry evicts the oldest (r1) rather than keeping it.
        val store = NetworkRequestStore(NetworkStoreConfig(maxRequests = 100, maxBufferBytes = 300))
        store.put(model("r1", postBody = "A".repeat(900)))
        assertEquals(1, store.size())
        assertTrue("a lone oversized record must not be discarded", store.get("r1") != null)

        store.put(model("r2"))
        assertEquals(1, store.size())
        assertTrue("body-heavy oldest must be evicted", store.get("r1") == null)
        assertEquals("r2", store.snapshot()[0].requestId)
        assertTrue(store.retainedBytes <= 300)
    }

    @Test
    fun byteCap_singleOversizedEntry_isRetained() {
        val store = NetworkRequestStore(NetworkStoreConfig(maxRequests = 10, maxBufferBytes = 200))
        store.put(model("big", postBody = "H".repeat(5000)))
        assertEquals(1, store.size())
        assertTrue("a lone oversized record must not be discarded", store.get("big") != null)
        assertTrue(store.retainedBytes >= 5000)
    }

    // ============ eviction semantics ============

    @Test
    fun upsert_existingKeepsOrderAndIsNotEvictedAsNewest() {
        val store = NetworkRequestStore(NetworkStoreConfig(maxRequests = 2, maxBufferBytes = Long.MAX_VALUE))
        store.put(model("r1"))
        store.put(model("r2"))
        store.upsert(
            requestId = "r1",
            create = { model("r1") },
            transform = { it.withStage(NetworkRequestStage.FINISHED) },
        )
        // r1 keeps its original (oldest) position: inserting r3 evicts r1, not r2.
        store.put(model("r3"))
        assertEquals(listOf("r2", "r3"), store.snapshot().map { it.requestId })
        assertEquals(1L, store.evictions)
    }

    @Test
    fun snapshot_neverReturnsEvictedRequests() {
        val store = NetworkRequestStore(NetworkStoreConfig(maxRequests = 1, maxBufferBytes = Long.MAX_VALUE))
        store.put(model("a"))
        store.put(model("b"))
        assertEquals(listOf("b"), store.snapshot().map { it.requestId })
        assertTrue(store.get("a") == null)
    }

    @Test
    fun clear_resetsCountersAndFootprint() {
        val store = NetworkRequestStore(NetworkStoreConfig(maxRequests = 2, maxBufferBytes = Long.MAX_VALUE))
        for (i in 1..5) store.put(model("r$i"))
        assertEquals(3L, store.evictions)
        assertTrue(store.retainedBytes > 0)
        store.clear()
        assertEquals(0, store.size())
        assertEquals(0L, store.retainedBytes)
        assertEquals(0L, store.evictions)
    }
}