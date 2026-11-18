package com.coppermux.cutools.ui.network

import com.coppermux.cutools.core.network.NetworkRequestModel
import com.coppermux.cutools.core.network.ResourceTiming
import com.coppermux.cutools.core.network.ResponseBodyResult
import com.coppermux.cutools.core.network.ResponseBodyStatus
import java.util.Locale

/**
 * The exhibitable state of the detail inspector for a single selected request.
 *
 * [request] and [body] are copies of genuine Chrome-derived models/results —
 * the inspector only renders them and never owns browser truth. [bodyStatus]
 * is null until a body fetch has resolved.
 */
data class NetworkDetailState(
    val request: NetworkRequestModel? = null,
    val tab: NetworkDetailTab = NetworkDetailTab.HEADERS,
    val bodyStatus: ResponseBodyStatus? = null,
    val bodyText: String? = null,
    val bodyBytes: ByteArray? = null,
    val bodyError: String? = null,
) {
    val hasRequest: Boolean get() = request != null

    /** True once a body fetch outcome has been resolved. */
    val bodyResolved: Boolean get() = bodyStatus != null

    /** True when the fetched body is representable as pretty-printable text. */
    val bodyIsText: Boolean get() = bodyStatus == ResponseBodyStatus.SUCCESS

    /** True when the fetched body decoded to binary (non-UTF-8) bytes. */
    val bodyIsBinary: Boolean get() = bodyStatus == ResponseBodyStatus.BINARY

    /** True when Chrome could not deliver a body (missing/evicted/unsupported). */
    val bodyUnavailable: Boolean get() = bodyStatus in
        setOf(ResponseBodyStatus.UNAVAILABLE, ResponseBodyStatus.UNSUPPORTED, ResponseBodyStatus.FAILED)

    /**
     * Body message for the "Response Body" tab: pretty text when available, an
     * explicit evicted/missing/unavailable capability message otherwise.
     */
    val bodyDisplay: String
        get() = when (bodyStatus) {
            ResponseBodyStatus.SUCCESS -> bodyText ?: ""
            ResponseBodyStatus.BINARY -> "(binary body)"
            ResponseBodyStatus.UNAVAILABLE ->
                bodyError ?: "Response body is unavailable (evicted or missing in Chrome)."
            ResponseBodyStatus.UNSUPPORTED ->
                bodyError ?: "Response body could not be decoded (unsupported encoding)."
            ResponseBodyStatus.FAILED ->
                bodyError ?: "Failed to retrieve the response body."
            null -> "Loading response body…"
        }

    /** A short body badge for the tab button (text/binary/unavailable). */
    val bodyBadge: String?
        get() = when (bodyStatus) {
            ResponseBodyStatus.SUCCESS -> "text"
            ResponseBodyStatus.BINARY -> "binary"
            ResponseBodyStatus.UNAVAILABLE -> "unavailable"
            ResponseBodyStatus.UNSUPPORTED -> "unsupported"
            ResponseBodyStatus.FAILED -> "failed"
            null -> null
        }

    // ================================================================
    // Headers tab
    // ================================================================

    /** "General" section rows: method, URL, status, type, mime, stage, duration. */
    val generalRows: List<NameValue>
        get() {
            val r = request ?: return emptyList()
            val rows = mutableListOf<NameValue>()
            r.method?.let { rows.add(NameValue("Request Method", it)) }
            rows.add(NameValue("Request URL", r.url ?: "—"))
            rows.add(NameValue("Status", statusSummary(r)))
            r.resourceType?.let { rows.add(NameValue("Resource Type", it)) }
            rows.add(NameValue("Stage", NetworkStatusStyle.stageLabel(r)))
            r.responseMimeType?.let { rows.add(NameValue("Response MIME Type", it)) }
            r.timing?.let { timing ->
                timing.responseEnd?.let {
                    rows.add(NameValue("Total Duration", totalDurationDisplay(timing)))
                }
            }
            return rows
        }

    /** Request-side header rows (Chrome-reported request headers). */
    val requestHeaderRows: List<NameValue>
        get() {
            val r = request ?: return emptyList()
            return if (r.requestHeaders.isEmpty()) {
                listOf(NameValue("Request Headers", "No request headers reported."))
            } else {
                r.requestHeaders.entries.sortedBy { it.key.lowercase() }.map { NameValue(it.key, it.value) }
            }
        }

    /** Response-side header rows (Chrome-reported response headers). */
    val responseHeaderRows: List<NameValue>
        get() {
            val r = request ?: return emptyList()
            return if (r.responseHeaders.isEmpty()) {
                listOf(NameValue("Response Headers", "No response headers reported."))
            } else {
                r.responseHeaders.entries.sortedBy { it.key.lowercase() }.map { NameValue(it.key, it.value) }
            }
        }

    // ================================================================
    // Timing tab
    // ================================================================

    /**
     * Timing breakdown rows: DNS, Connection, SSL, Send, TTFB and Content
     * Download, derived only from genuine Chrome [ResourceTiming] endpoints.
     */
    val timingRows: List<NameValue>
        get() {
            val r = request ?: return emptyList()
            val timing = r.timing ?: return listOf(NameValue("Timing", "No timing data reported by Chrome."))
            val rows = mutableListOf<NameValue>()
            timing.dnsDuration?.let { rows.add(NameValue("DNS Lookup", NetworkDetailModel.formatMs(it))) }
            timing.connectionDuration?.let { rows.add(NameValue("Connecting", NetworkDetailModel.formatMs(it))) }
            timing.sslDuration?.let { rows.add(NameValue("SSL/TLS", NetworkDetailModel.formatMs(it))) }
            timing.sendDuration?.let { rows.add(NameValue("Sending Request", NetworkDetailModel.formatMs(it))) }
            timing.ttfbDuration?.let { rows.add(NameValue("Waiting (TTFB)", NetworkDetailModel.formatMs(it))) }
            timing.contentDownloadDuration?.let {
                rows.add(NameValue("Content Download", NetworkDetailModel.formatMs(it)))
            }
            if (rows.isEmpty()) {
                rows.add(NameValue("Timing", "Timing stages were not reported by Chrome."))
            }
            return rows
        }

    private fun statusSummary(r: NetworkRequestModel): String = buildString {
        when {
            r.isFailed -> append("failed")
            r.responseStatus != null -> append(r.responseStatus)
            r.isPending -> append("pending")
            else -> append("response")
        }
        r.responseStatusText?.let { append(' ').append(it) }
    }

    private fun totalDurationDisplay(timing: ResourceTiming): String {
        val start = timing.dnsStart ?: timing.connectStart ?: timing.sendStart
        if (start == null) {
            return NetworkDetailModel.formatMs(timing.contentDownloadDuration ?: 0.0)
        }
        return timing.responseEnd?.let { NetworkDetailModel.formatMs(it - start) } ?: "unavailable"
    }
}

/**
 * Immutable flow controller for the detail-inspector pane.
 */
data class NetworkDetailViewModel(
    val state: NetworkDetailState = NetworkDetailState(),
    val bodyLoading: Boolean = false,
) {
    /** Whether a request is currently selected for inspection. */
    val hasSelection: Boolean get() = state.hasRequest

    /** Selects [request], resetting the body view. */
    fun select(request: NetworkRequestModel): NetworkDetailViewModel =
        copy(state = NetworkDetailState(request = request))

    /** Mirrors a selected request whose model was refreshed by the engine. */
    fun refresh(request: NetworkRequestModel?): NetworkDetailViewModel {
        val current = state.request ?: return this
        if (request == null || request.requestId != current.requestId) return this
        return copy(state = state.copy(request = request))
    }

    /** Switches the active [NetworkDetailTab]. */
    fun setTab(tab: NetworkDetailTab): NetworkDetailViewModel {
        if (state.tab == tab) return this
        return copy(state = state.copy(tab = tab))
    }

    /** Marks a body fetch as in progress. */
    fun loadingBody(): NetworkDetailViewModel = copy(bodyLoading = true)

    /** Applies a resolved [ResponseBodyResult] to the selected request. */
    fun applyBody(result: ResponseBodyResult): NetworkDetailViewModel =
        copy(
            bodyLoading = false,
            state = state.copy(
                bodyStatus = result.status,
                bodyText = result.text,
                bodyBytes = result.bytes,
                bodyError = result.error,
            ),
        )
}

/** Presentation helpers for the detail inspector that stay pure and testable. */
object NetworkDetailModel {

    /** Formats a millisecond duration: whole ms as an integer, else 1 decimal. */
    fun formatMs(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() + " ms"
        else String.format(Locale.US, "%.1f ms", rounded)
    }

    /** Total end-to-end duration of a [ResourceTiming], when derivable. */
    fun totalDuration(timing: ResourceTiming): String? {
        val start = timing.dnsStart ?: timing.connectStart ?: timing.sendStart ?: return null
        val end = timing.responseEnd ?: return null
        return formatMs(end - start)
    }
}