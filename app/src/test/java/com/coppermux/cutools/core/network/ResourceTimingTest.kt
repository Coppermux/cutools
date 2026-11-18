package com.coppermux.cutools.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ResourceTiming] duration calculation and [ResourceTimingFormatter].
 * Verifies DNS, SSL, connection, TTFB, latency, and content-download accuracy.
 */
class ResourceTimingTest {

    private fun timing(): ResourceTiming = ResourceTiming(
        dnsStart = 0.0,
        dnsEnd = 40.0,
        connectStart = 40.0,
        connectEnd = 120.0,
        sslStart = 120.0,
        sslEnd = 160.0,
        sendStart = 160.0,
        sendEnd = 220.0,
        receiveHeadersEnd = 320.0,
        responseEnd = 480.0,
    )

    @Test
    fun dnsDuration_isDnsEndMinusDnsStart() {
        assertEquals(40.0, timing().dnsDuration!!, 0.001)
    }

    @Test
    fun sslDuration_isSslEndMinusSslStart() {
        assertEquals(40.0, timing().sslDuration!!, 0.001)
    }

    @Test
    fun connectionDuration_isConnectEndMinusConnectStart() {
        assertEquals(80.0, timing().connectionDuration!!, 0.001)
    }

    @Test
    fun sendDuration_isSendEndMinusSendStart() {
        assertEquals(60.0, timing().sendDuration!!, 0.001)
    }

    @Test
    fun ttfb_isReceiveHeadersEndMinusSendStart() {
        // 320 - 160 = 160ms from request send start to first byte.
        assertEquals(160.0, timing().ttfbDuration!!, 0.001)
    }

    @Test
    fun latency_isReceiveHeadersEndMinusDnsStart() {
        // End-to-end to first byte measured from DNS start.
        assertEquals(320.0, timing().latencyDuration!!, 0.001)
    }

    @Test
    fun contentDownload_isResponseEndMinusReceiveHeadersEnd() {
        assertEquals(160.0, timing().contentDownloadDuration!!, 0.001)
    }

    @Test
    fun incompleteTiming_yieldsNullDurations() {
        val t = ResourceTiming(dnsStart = 0.0, dnsEnd = 40.0)
        assertEquals(40.0, t.dnsDuration!!, 0.001)
        assertNull(t.sslDuration)
        assertNull(t.ttfbDuration)
        assertNull(t.contentDownloadDuration)
    }

    @Test
    fun emptyTiming_hasAllNullDurations() {
        val t = ResourceTiming()
        assertNull(t.dnsDuration)
        assertNull(t.sslDuration)
        assertNull(t.ttfbDuration)
        assertNull(t.latencyDuration)
        assertNull(t.contentDownloadDuration)
    }

    @Test
    fun fromParams_parsesNumericFields() {
        val raw = mapOf<Any?, Any?>(
            "dnsStart" to 0.0,
            "dnsEnd" to 50.0,
            "connectStart" to 50.0,
            "connectEnd" to 150.0,
            "sslStart" to 150.0,
            "sslEnd" to 200.0,
            "sendStart" to 200.0,
            "sendEnd" to 250.0,
            "receiveHeadersEnd" to 400.0,
            "responseEnd" to 500.0,
        )
        val t = ResourceTiming.fromParams(raw)
        assertTrue(t != null)
        assertEquals(50.0, t!!.dnsDuration!!, 0.001)
        assertEquals(50.0, t!!.sslDuration!!, 0.001)
        assertEquals(200.0, t!!.ttfbDuration!!, 0.001)
        assertEquals(100.0, t!!.contentDownloadDuration!!, 0.001)
    }

    @Test
    fun fromParams_nonMap_orMissing_returnsNull() {
        assertNull(ResourceTiming.fromParams("not-a-map"))
        assertNull(ResourceTiming.fromParams(null))
        assertNull(ResourceTiming.fromParams(mapOf<Any?, Any?>("unrelated" to 1)))
    }

    // ============ formatter ============

    @Test
    fun formatter_rendersOnlyAvailableStages() {
        val formatted = ResourceTimingFormatter.format(timing())
        assertTrue(formatted.contains("DNS: 40ms"))
        assertTrue(formatted.contains("SSL: 40ms"))
        assertTrue(formatted.contains("TTFB: 160ms"))
        assertTrue(formatted.contains("Latency: 320ms"))
        assertTrue(formatted.contains("Download: 160ms"))
    }

    @Test
    fun formatter_emptyTiming_isUnavailable() {
        assertEquals("Timing: unavailable", ResourceTimingFormatter.format(ResourceTiming()))
    }
}