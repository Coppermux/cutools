package com.coppermux.cutools.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [NetworkRequestModel] lifecycle model and the thread-safe
 * [NetworkRequestStore] keyed by requestId.
 */
class NetworkModelsTest {

    private fun model(stage: NetworkRequestStage): NetworkRequestModel = NetworkRequestModel(
        requestId = "r1",
        stage = stage,
        url = "https://example.com/",
        sessionId = "session-1",
        targetId = "target-1",
    )

    @Test
    fun pending_model_hasPendingFlagAndNoResponse() {
        val m = model(NetworkRequestStage.PENDING)
        assertTrue(m.isPending)
        assertFalse(m.hasResponse)
        assertFalse(m.isTerminal)
        assertEquals(NetworkRequestStage.PENDING, m.stage)
    }

    @Test
    fun responseReceived_hasResponseButNotTerminal() {
        val m = model(NetworkRequestStage.RESPONSE_RECEIVED)
        assertTrue(m.hasResponse)
        assertFalse(m.isTerminal)
        assertFalse(m.isFinished)
        assertFalse(m.isFailed)
    }

    @Test
    fun finished_isTerminalAndFinished() {
        val m = model(NetworkRequestStage.FINISHED)
        assertTrue(m.isFinished)
        assertTrue(m.isTerminal)
        assertFalse(m.isFailed)
    }

    @Test
    fun failed_isTerminalAndFailed() {
        val m = model(NetworkRequestStage.FAILED)
        assertTrue(m.isFailed)
        assertTrue(m.isTerminal)
        assertFalse(m.isFinished)
    }

    @Test
    fun withStage_promotesToNewStage() {
        val pending = model(NetworkRequestStage.PENDING)
        val finished = pending.withStage(NetworkRequestStage.FINISHED)
        assertEquals(NetworkRequestStage.FINISHED, finished.stage)
        assertEquals(pending.requestId, finished.requestId)
        assertTrue(finished.isFinished)
    }

    @Test
    fun stage_fromCdpMethod_mapsTheFourLifecycleEvents() {
        assertEquals(NetworkRequestStage.PENDING, NetworkRequestStage.fromCdpMethod("Network.requestWillBeSent"))
        assertEquals(NetworkRequestStage.RESPONSE_RECEIVED, NetworkRequestStage.fromCdpMethod("Network.responseReceived"))
        assertEquals(NetworkRequestStage.FINISHED, NetworkRequestStage.fromCdpMethod("Network.loadingFinished"))
        assertEquals(NetworkRequestStage.FAILED, NetworkRequestStage.fromCdpMethod("Network.loadingFailed"))
        assertEquals(null, NetworkRequestStage.fromCdpMethod("Network.unknownEvent"))
    }

    // ============ NetworkRequestStore ============

    @Test
    fun store_insertAndGet_byRequestId() {
        val store = NetworkRequestStore()
        val model = model(NetworkRequestStage.PENDING)
        store.put(model)
        assertEquals(model, store.get("r1"))
        assertEquals(1, store.size())
        assertEquals(null, store.get("missing"))
    }

    @Test
    fun store_upsert_createsWhenAbsent() {
        val store = NetworkRequestStore()
        store.upsert(
            requestId = "r1",
            create = { model(NetworkRequestStage.PENDING) },
            transform = { throw AssertionError("transform must not run on create") },
        )
        assertEquals(NetworkRequestStage.PENDING, store.get("r1")!!.stage)
    }

    @Test
    fun store_upsert_transformsExisting() {
        val store = NetworkRequestStore()
        store.put(model(NetworkRequestStage.PENDING))
        store.upsert(
            requestId = "r1",
            create = { throw AssertionError("create must not run on update") },
            transform = { it.withStage(NetworkRequestStage.FINISHED) },
        )
        assertEquals(NetworkRequestStage.FINISHED, store.get("r1")!!.stage)
    }

    @Test
    fun store_clear_removesAll() {
        val store = NetworkRequestStore()
        store.put(model(NetworkRequestStage.PENDING))
        store.put(model(NetworkRequestStage.PENDING).copy(requestId = "r2"))
        store.clear()
        assertEquals(0, store.size())
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun store_snapshot_isDeterministicOrder() {
        val store = NetworkRequestStore()
        store.put(model(NetworkRequestStage.PENDING).copy(requestId = "z"))
        store.put(model(NetworkRequestStage.PENDING).copy(requestId = "a"))
        store.put(model(NetworkRequestStage.PENDING).copy(requestId = "m"))
        assertEquals(listOf("a", "m", "z"), store.snapshot().map { it.requestId })
    }

    @Test
    fun store_snapshot_isImmutableCopy() {
        val store = NetworkRequestStore()
        store.put(model(NetworkRequestStage.PENDING))
        val snap = store.snapshot()
        store.put(model(NetworkRequestStage.FINISHED))
        // The earlier snapshot is unaffected by the later mutation.
        assertEquals(NetworkRequestStage.PENDING, snap[0].stage)
        assertEquals(NetworkRequestStage.FINISHED, store.get("r1")!!.stage)
    }

    // ============ NetworkState ============

    @Test
    fun state_empty_isInactiveWithNoRequests() {
        val s = NetworkState.empty()
        assertTrue(s.isInactive)
        assertFalse(s.isReady)
        assertFalse(s.hasRequests)
        assertEquals(0, s.requests.size)
    }

    @Test
    fun state_counts_areDerivedFromStages() {
        fun req(id: String, stage: NetworkRequestStage) = model(stage).copy(requestId = id)
        val s = NetworkState(
            requests = listOf(
                req("1", NetworkRequestStage.PENDING),
                req("2", NetworkRequestStage.RESPONSE_RECEIVED),
                req("3", NetworkRequestStage.FINISHED),
                req("4", NetworkRequestStage.FINISHED),
                req("5", NetworkRequestStage.FAILED),
            )
        )
        assertEquals(1, s.pendingCount)
        assertEquals(1, s.responseCount)
        assertEquals(2, s.finishedCount)
        assertEquals(1, s.failedCount)
        assertTrue(s.hasRequests)
    }
}