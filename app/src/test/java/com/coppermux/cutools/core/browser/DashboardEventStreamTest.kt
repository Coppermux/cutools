package com.coppermux.cutools.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 18 — pure unit tests for the centralized interceptor event stream:
 * arrival ordering / id assignment, bounded retention, listener fan-out, and
 * presentation (filter/search) state ownership.
 */
class DashboardEventStreamTest {

    private lateinit var stream: DashboardEventStream
    private val snapshots = mutableListOf<DashboardStreamState>()

    @Before
    fun setUp() {
        snapshots.clear()
        stream = DashboardEventStream()
        stream.addListener(object : DashboardEventStreamListener {
            override fun onDashboardStateChanged(state: DashboardStreamState) {
                snapshots.add(state)
            }
        })
    }

    private fun event(timestamp: Long, url: String = "https://example.com/$timestamp"): DashboardEvent =
        DashboardEventClassifier.buildNetworkRequestEvent(
            url = url,
            method = "GET",
            isForMainFrame = false,
            timestamp = timestamp,
        )

    @Test
    fun append_stampsMonotonicIdsAcrossEvents() {
        val first = stream.append(event(1_000))
        val second = stream.append(event(2_000))
        assertTrue("ids strictly increase", first.id < second.id)
        assertEquals(1L, first.id)
        assertEquals(2L, second.id)
    }

    @Test
    fun append_keepsChronologicalArrivalOrder() {
        stream.append(event(1_000, "https://a.com"))
        stream.append(event(2_000, "https://b.com"))
        stream.append(event(3_000, "https://c.com"))
        assertEquals(
            listOf("https://a.com", "https://b.com", "https://c.com"),
            stream.state.events.map { it.url },
        )
        assertEquals(3, stream.state.eventCount)
        assertEquals(3, stream.state.visibleCount)
    }

    @Test
    fun append_evictsOldestWhenBounded() {
        val bounded = DashboardEventStream(maxEntries = 3)
        bounded.append(event(1_000, "a.com"))
        bounded.append(event(2_000, "b.com"))
        bounded.append(event(3_000, "c.com"))
        bounded.append(event(4_000, "d.com"))
        assertEquals(listOf("b.com", "c.com", "d.com"), bounded.state.events.map { it.url })
    }

    @Test
    fun listeners_receiveEverySnapshot() {
        stream.append(event(1_000))
        stream.append(event(2_000))
        assertEquals(2, snapshots.size)
        assertEquals(2, snapshots.last().eventCount)
    }

    @Test
    fun setFilter_updatesPresentationStateOnly() {
        stream.append(event(1_000))
        stream.append(DashboardEventClassifier.buildConsoleEvent("error", "boom", 2_000))
        stream.setFilter(DashboardFilter.CONSOLE)
        assertEquals(DashboardFilter.CONSOLE, stream.state.filter)
        assertEquals(2, stream.state.eventCount)
        assertEquals(1, stream.state.visibleCount)
        assertTrue(stream.state.visibleEvents.first().isConsole)
    }

    @Test
    fun setSearchQuery_updatesPresentationStateOnly() {
        stream.append(DashboardEventClassifier.buildConsoleEvent("warning", "deprecated api", 1_000))
        stream.append(event(2_000, "https://example.com/app.js"))
        stream.setSearchQuery("deprecated")
        assertEquals("deprecated", stream.state.searchQuery)
        assertEquals(2, stream.state.eventCount)
        assertEquals(listOf("deprecated api"), stream.state.visibleEvents.map { it.message })
    }

    @Test
    fun filterAndSearch_areIntersection() {
        stream.append(DashboardEventClassifier.buildConsoleEvent("log", "hello", 1_000))
        stream.append(DashboardEventClassifier.buildConsoleEvent("warning", "deprecated", 2_000))
        stream.append(DashboardEventClassifier.buildNetworkRequestEvent(
            url = "https://example.com/app.js", method = "GET", isForMainFrame = false, timestamp = 3_000,
        ))
        stream.setFilter(DashboardFilter.CONSOLE)
        stream.setSearchQuery("deprecated")
        assertEquals(1, stream.state.visibleCount)
        assertEquals("deprecated", stream.state.visibleEvents.single().message)
        assertTrue(stream.state.isFiltered)
    }

    @Test
    fun clear_removesEventsButKeepsPresentationState() {
        stream.append(event(1_000))
        stream.setFilter(DashboardFilter.ERRORS)
        stream.setSearchQuery("x")
        stream.clear()
        assertEquals(0, stream.state.eventCount)
        assertEquals(DashboardFilter.ERRORS, stream.state.filter)
        assertEquals("x", stream.state.searchQuery)
    }

    @Test
    fun removeListener_stopsDelivery() {
        val received = mutableListOf<DashboardStreamState>()
        val listener = object : DashboardEventStreamListener {
            override fun onDashboardStateChanged(state: DashboardStreamState) { received.add(state) }
        }
        stream.addListener(listener)
        stream.append(event(1_000))
        stream.removeListener(listener)
        stream.append(event(2_000))
        assertEquals(1, received.size)
    }

    @Test
    fun staleTimestamp_isFilledWithWallClock() {
        val stamped = stream.append(event(0))
        assertTrue(stamped.timestamp > 0)
    }

    @Test
    fun emptyState_hasNoVisibleEvents() {
        assertTrue(stream.state.events.isEmpty())
        assertNull(stream.state.events.firstOrNull())
        assertEquals(0, stream.state.eventCount)
        assertEquals(0, stream.state.visibleCount)
    }

    @Test
    fun append_whenPaused_freezesFeedAndDropsIncomingEvents() {
        stream.append(event(1_000, "https://a.com"))
        assertEquals(1, stream.state.eventCount)
        assertTrue(stream.isRecording)
        assertTrue(stream.state.isRecording)

        // Pause recording
        stream.setRecording(false)
        assertEquals(false, stream.isRecording)
        assertEquals(false, stream.state.isRecording)

        // Incoming events during pause are dropped; state does not change
        val countBefore = stream.state.eventCount
        val snapshotsBefore = snapshots.size
        val dropped = stream.append(event(2_000, "https://b.com"))

        assertEquals(countBefore, stream.state.eventCount)
        assertEquals(snapshotsBefore, snapshots.size)
        assertEquals(listOf("https://a.com"), stream.state.events.map { it.url })

        // Resume recording
        stream.setRecording(true)
        assertTrue(stream.isRecording)
        assertTrue(stream.state.isRecording)

        // New events resume ingestion
        stream.append(event(3_000, "https://c.com"))
        assertEquals(2, stream.state.eventCount)
        assertEquals(listOf("https://a.com", "https://c.com"), stream.state.events.map { it.url })
    }

    @Test
    fun toggleRecording_togglesBetweenActiveAndPaused() {
        assertTrue(stream.isRecording)
        val paused = stream.toggleRecording()
        assertEquals(false, paused)
        assertEquals(false, stream.isRecording)
        assertEquals(false, stream.state.isRecording)

        val resumed = stream.toggleRecording()
        assertEquals(true, resumed)
        assertEquals(true, stream.isRecording)
        assertEquals(true, stream.state.isRecording)
    }

    // ================================================================
    // upsertByRequestId lifecycle & deduplication
    // ================================================================

    @Test
    fun upsertByRequestId_createsThenUpdatesInPlacePreservingIdAndPosition() {
        val created = stream.upsertByRequestId(
            requestId = "req-1",
            create = {
                DashboardEvent(
                    requestId = "req-1",
                    category = DashboardEventCategory.NETWORK,
                    severity = DashboardSeverity.SUCCESS,
                    kind = "REQUEST",
                    url = "https://example.com/api",
                    timestamp = 1000L,
                )
            },
            transform = { it },
        )
        assertNotNull(created)
        assertEquals(1L, created!!.id)
        assertEquals("req-1", created.requestId)
        assertNull(created.statusCode)

        // Append another event to the stream
        stream.append(event(1500L, "https://other.com"))
        assertEquals(2, stream.state.eventCount)

        // Now update req-1 with response received
        val updated = stream.upsertByRequestId(
            requestId = "req-1",
            create = { error("Should not create") },
            transform = { existing ->
                existing.copy(
                    statusCode = 200,
                    responseHeaders = mapOf("Content-Type" to "application/json"),
                )
            },
        )
        assertNotNull(updated)
        // Original ID and position preserved!
        assertEquals(1L, updated!!.id)
        assertEquals(200, updated.statusCode)
        assertEquals(mapOf("Content-Type" to "application/json"), updated.responseHeaders)
        assertEquals(2, stream.state.eventCount)
        assertEquals(listOf(1L, 2L), stream.state.events.map { it.id })
    }

    @Test
    fun upsertByRequestId_repeatedUrlsWithDifferentRequestIdsAreSeparateEvents() {
        val first = stream.upsertByRequestId(
            requestId = "req-alpha",
            create = {
                DashboardEvent(
                    requestId = "req-alpha",
                    category = DashboardEventCategory.NETWORK,
                    severity = DashboardSeverity.SUCCESS,
                    kind = "REQUEST",
                    url = "https://example.com/poll",
                    timestamp = 1000L,
                )
            },
            transform = { it },
        )
        val second = stream.upsertByRequestId(
            requestId = "req-beta",
            create = {
                DashboardEvent(
                    requestId = "req-beta",
                    category = DashboardEventCategory.NETWORK,
                    severity = DashboardSeverity.SUCCESS,
                    kind = "REQUEST",
                    url = "https://example.com/poll",
                    timestamp = 2000L,
                )
            },
            transform = { it },
        )

        assertEquals(2, stream.state.eventCount)
        assertEquals(1L, first!!.id)
        assertEquals(2L, second!!.id)
        assertEquals("req-alpha", stream.state.events[0].requestId)
        assertEquals("req-beta", stream.state.events[1].requestId)
    }

    @Test
    fun upsertByRequestId_boundedStreamEvictsOldestAndMaintainsIndex() {
        val bounded = DashboardEventStream(maxEntries = 2)
        bounded.upsertByRequestId("r1", { DashboardEvent(requestId = "r1", category = DashboardEventCategory.NETWORK, severity = DashboardSeverity.SUCCESS, kind = "REQ", url = "1.com", timestamp = 1L) }) { it }
        bounded.upsertByRequestId("r2", { DashboardEvent(requestId = "r2", category = DashboardEventCategory.NETWORK, severity = DashboardSeverity.SUCCESS, kind = "REQ", url = "2.com", timestamp = 2L) }) { it }
        // r3 causes r1 to be evicted
        bounded.upsertByRequestId("r3", { DashboardEvent(requestId = "r3", category = DashboardEventCategory.NETWORK, severity = DashboardSeverity.SUCCESS, kind = "REQ", url = "3.com", timestamp = 3L) }) { it }

        assertEquals(listOf("2.com", "3.com"), bounded.state.events.map { it.url })

        // Updating r1 now creates anew since it was evicted
        val r1Recreated = bounded.upsertByRequestId("r1", { DashboardEvent(requestId = "r1", category = DashboardEventCategory.NETWORK, severity = DashboardSeverity.SUCCESS, kind = "REQ", url = "1.com", timestamp = 4L) }) { it }
        assertNotNull(r1Recreated)
        assertEquals(listOf("3.com", "1.com"), bounded.state.events.map { it.url })
    }

    @Test
    fun upsertByRequestId_whenPaused_isDropped() {
        stream.setRecording(false)
        val dropped = stream.upsertByRequestId(
            requestId = "r1",
            create = { DashboardEvent(requestId = "r1", category = DashboardEventCategory.NETWORK, severity = DashboardSeverity.SUCCESS, kind = "REQ", url = "1.com", timestamp = 1L) },
            transform = { it },
        )
        assertNull(dropped)
        assertEquals(0, stream.state.eventCount)
    }

    @Test
    fun reset_clearsEventsAndRestoresDefaultPresentationAndRecording() {
        stream.append(event(1_000))
        stream.setFilter(DashboardFilter.ERRORS)
        stream.setSearchQuery("query")
        stream.setRecording(false)

        stream.reset()

        assertEquals(0, stream.state.eventCount)
        assertEquals(DashboardFilter.ALL, stream.state.filter)
        assertEquals("", stream.state.searchQuery)
        assertTrue(stream.state.isRecording)
    }
}