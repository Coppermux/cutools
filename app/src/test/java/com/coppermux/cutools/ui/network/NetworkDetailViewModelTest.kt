package com.coppermux.cutools.ui.network

import com.coppermux.cutools.core.network.NetworkRequestModel
import com.coppermux.cutools.core.network.NetworkRequestStage
import com.coppermux.cutools.core.network.ResourceTiming
import com.coppermux.cutools.core.network.ResponseBodyResult
import com.coppermux.cutools.core.network.ResponseBodyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for Phase 5D detail-inspector view-model flow
 * ([NetworkDetailViewModel], [NetworkDetailState], [NetworkDetailModel]).
 */
class NetworkDetailViewModelTest {

    private fun model(
        id: String = "req1",
        stage: NetworkRequestStage = NetworkRequestStage.FINISHED,
        status: Int? = 200,
        statusText: String? = "OK",
        requestHeaders: Map<String, String> = emptyMap(),
        responseHeaders: Map<String, String> = emptyMap(),
        timing: ResourceTiming? = null,
        mime: String? = null,
    ) = NetworkRequestModel(
        requestId = id,
        stage = stage,
        url = "https://api.example.com/users/42",
        method = "GET",
        resourceType = "xhr",
        responseStatus = status,
        responseStatusText = statusText,
        responseMimeType = mime,
        requestHeaders = requestHeaders,
        responseHeaders = responseHeaders,
        timing = timing,
        sessionId = "s1",
    )

    // ============ selection flow ============

    @Test
    fun select_setsRequestAndResetsBody() {
        val vm0 = NetworkDetailViewModel()
            .applyBody(ResponseBodyResult(status = ResponseBodyStatus.SUCCESS, text = "old"))
        val vm = vm0.select(model())
        assertTrue(vm.hasSelection)
        assertEquals("req1", vm.state.request?.requestId)
        assertNull(vm.state.bodyStatus)
        assertFalse(vm.bodyLoading)
    }

    @Test
    fun emptyViewModel_hasNoSelection() {
        assertFalse(NetworkDetailViewModel().hasSelection)
        assertNull(NetworkDetailViewModel().state.request)
    }

    @Test
    fun setTab_switchesTab() {
        val vm = NetworkDetailViewModel().setTab(NetworkDetailTab.BODY)
        assertEquals(NetworkDetailTab.BODY, vm.state.tab)
    }

    @Test
    fun refresh_updatesOnlySameRequest() {
        val selected = model()
        val vm = NetworkDetailViewModel().select(selected)
        val refreshed = model(status = 201)
        val next = vm.refresh(refreshed)
        assertEquals(201, next.state.request?.responseStatus)

        val other = vm.refresh(model(id = "other"))
        assertEquals("req1", other.state.request?.requestId)
    }

    @Test
    fun refresh_ignoresNullSelection() {
        val vm = NetworkDetailViewModel().select(model())
        assertTrue(vm.refresh(null).hasSelection)
    }

    // ============ body flow ============

    @Test
    fun applyBody_deliversText() {
        val vm = NetworkDetailViewModel().loadingBody()
        val next = vm.applyBody(ResponseBodyResult(status = ResponseBodyStatus.SUCCESS, text = "{\"a\":1}"))
        assertFalse(next.bodyLoading)
        assertTrue(next.state.bodyIsText)
        assertFalse(next.state.bodyIsBinary)
        assertEquals("{\"a\":1}", next.state.bodyDisplay)
        assertEquals("text", next.state.bodyBadge)
    }

    @Test
    fun applyBody_deliversBinaryBytes() {
        val vm = NetworkDetailViewModel().applyBody(
            ResponseBodyResult(status = ResponseBodyStatus.BINARY, bytes = byteArrayOf(0x00, 0x01, 0xFF.toByte())),
        )
        assertTrue(vm.state.bodyIsBinary)
        assertFalse(vm.state.bodyIsText)
        assertEquals("(binary body)", vm.state.bodyDisplay)
        assertEquals("binary", vm.state.bodyBadge)
    }

    @Test
    fun applyBody_evictedUnavailable() {
        val vm = NetworkDetailViewModel().applyBody(
            ResponseBodyResult(status = ResponseBodyStatus.UNAVAILABLE, error = "No resource with given identifier found"),
        )
        assertTrue(vm.state.bodyUnavailable)
        assertEquals("unavailable", vm.state.bodyBadge)
        assertEquals("No resource with given identifier found", vm.state.bodyDisplay)
    }

    @Test
    fun applyBody_evictedDefaultMessage() {
        val vm = NetworkDetailViewModel().applyBody(ResponseBodyResult(status = ResponseBodyStatus.UNAVAILABLE))
        assertTrue(vm.state.bodyDisplay.contains("evicted"))
    }

    @Test
    fun applyBody_unsupportedAndFailedMessages() {
        val unsupported = NetworkDetailViewModel().applyBody(
            ResponseBodyResult(status = ResponseBodyStatus.UNSUPPORTED, error = "base64 decode failed"),
        )
        assertTrue(unsupported.state.bodyUnavailable)
        assertEquals("unsupported", unsupported.state.bodyBadge)

        val failed = NetworkDetailViewModel().applyBody(
            ResponseBodyResult(status = ResponseBodyStatus.FAILED, error = "boom"),
        )
        assertTrue(failed.state.bodyUnavailable)
        assertEquals("failed", failed.state.bodyBadge)
    }

    @Test
    fun bodyUnresolved_loadingMessage() {
        val vm = NetworkDetailViewModel().loadingBody()
        assertEquals("Loading response body…", vm.state.bodyDisplay)
        assertTrue(vm.bodyLoading)
        assertNull(vm.state.bodyBadge)
        assertFalse(vm.state.bodyResolved)
    }

    // ============ headers rows ============

    @Test
    fun generalRows_includeMethodUrlAndStatus() {
        val rows = NetworkDetailViewModel().select(model()).state.generalRows
        val labels = rows.map { it.label }
        assertTrue(labels.any { it.contains("method", ignoreCase = true) })
        assertTrue(labels.any { it.contains("request url", ignoreCase = true) })
        assertTrue(labels.any { it.contains("status", ignoreCase = true) })
    }

    @Test
    fun requestHeaderRows_areSortedByName() {
        val state = NetworkDetailViewModel().select(
            model(requestHeaders = mapOf("z-z" to "1", "a-a" to "2")),
        ).state
        val rows = state.requestHeaderRows
        assertEquals(listOf("a-a", "z-z"), rows.map { it.label })
        assertEquals("2", rows.first().value)
    }

    @Test
    fun responseHeaderRows_presentAndSorted() {
        val state = NetworkDetailViewModel().select(
            model(responseHeaders = mapOf("Content-Type" to "application/json")),
        ).state
        val rows = state.responseHeaderRows
        assertEquals(1, rows.size)
        assertEquals("content-type", rows.first().label.lowercase())
    }

    @Test
    fun emptyHeaders_showPlaceholderRow() {
        val state = NetworkDetailViewModel().select(model()).state
        assertEquals(1, state.requestHeaderRows.size)
        assertTrue(state.requestHeaderRows.first().value.contains("No request headers"))
        assertEquals(1, state.responseHeaderRows.size)
    }

    @Test
    fun failedStatus_showsInGeneralRows() {
        val state = NetworkDetailViewModel().select(
            model(status = 404, statusText = "Not Found"),
        ).state
        val statusRow = state.generalRows.first { it.label.equals("Status", ignoreCase = true) }
        assertEquals("404 Not Found", statusRow.value)
    }

    // ============ timing rows ============

    @Test
    fun timingRows_deriveFromGenuineEndpoints() {
        val timing = ResourceTiming(
            dnsStart = 0.0, dnsEnd = 2.0,
            sslStart = 2.0, sslEnd = 4.0,
            sendStart = 4.0, receiveHeadersEnd = 5.5,
            responseEnd = 15.5,
        )
        val state = NetworkDetailViewModel().select(model(timing = timing)).state
        val rows = state.timingRows
        val byLabel = rows.associateBy { it.label }
        assertEquals("2 ms", byLabel["DNS Lookup"]?.value)
        assertEquals("2 ms", byLabel["SSL/TLS"]?.value)
        assertEquals("1.5 ms", byLabel["Waiting (TTFB)"]?.value)
        assertEquals("10 ms", byLabel["Content Download"]?.value)
    }

    @Test
    fun noTiming_yieldsUnavailableRow() {
        val state = NetworkDetailViewModel().select(model()).state
        val rows = state.timingRows
        assertEquals(1, rows.size)
        assertTrue(rows.first().value.contains("No timing data"))
    }

    @Test
    fun incompleteTiming_omitsUnknownStages() {
        val timing = ResourceTiming(dnsStart = 0.0, dnsEnd = 1.0)
        val rows = NetworkDetailViewModel().select(model(timing = timing)).state.timingRows
        assertEquals(listOf("DNS Lookup"), rows.map { it.label })
    }

    // ============ formatting helpers ============

    @Test
    fun formatMs_wholeNumbersAsIntegers() {
        assertEquals("2 ms", NetworkDetailModel.formatMs(2.0))
        assertEquals("0 ms", NetworkDetailModel.formatMs(0.0))
        assertEquals("123 ms", NetworkDetailModel.formatMs(123.0))
    }

    @Test
    fun formatMs_fractionalOneDecimal() {
        assertEquals("1.5 ms", NetworkDetailModel.formatMs(1.5))
        assertEquals("10.3 ms", NetworkDetailModel.formatMs(10.34))
    }

    @Test
    fun detailState_bodyBadgeForEachOutcome() {
        assertEquals("unsupported", NetworkDetailViewModel().applyBody(
            ResponseBodyResult(status = ResponseBodyStatus.UNSUPPORTED),
        ).state.bodyBadge)
        assertEquals("unavailable", NetworkDetailViewModel().applyBody(
            ResponseBodyResult(status = ResponseBodyStatus.UNAVAILABLE),
        ).state.bodyBadge)
    }

    @Test
    fun generalRows_totalDurationUsesResponseEnd() {
        val timing = ResourceTiming(dnsStart = 0.0, responseEnd = 9.0)
        val state = NetworkDetailViewModel().select(model(timing = timing)).state
        val duration = state.generalRows.firstOrNull { it.label.equals("total duration", ignoreCase = true) }
        assertEquals("9 ms", duration?.value)
    }
}