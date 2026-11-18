package com.coppermux.cutools.core.network

import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo

/**
 * Applies genuine Chrome `Network` domain events to the in-memory
 * [NetworkRequestStore], advancing each request across its lifecycle.
 *
 * Handled events:
 *  - `Network.requestWillBeSent`   → inserts a [NetworkRequestStage.PENDING] entry
 *  - `Network.responseReceived`    → promotes to [NetworkRequestStage.RESPONSE_RECEIVED]
 *  - `Network.loadingFinished`     → promotes to [NetworkRequestStage.FINISHED] (terminal)
 *  - `Network.loadingFailed`       → promotes to [NetworkRequestStage.FAILED] (terminal)
 *
 * Only Chrome-supplied fields are populated; malformed payloads (non-map params,
 * missing requestId) are tolerated and return without mutating the store, so the
 * event pipeline never crashes.
 *
 * The store is atomic per requestId: if multiple events for the same request
 * arrive concurrently, each is applied serially without a lost update.
 */
class CdpNetworkEventListener(private val store: NetworkRequestStore) {

    /**
     * Applies a classified Network event for the active [session]. Events for
     * other sessions must be filtered by the caller before reaching here.
     * Returns the updated [NetworkRequestModel] or null if the event was ignored.
     */
    fun apply(event: Event, session: SessionInfo): NetworkRequestModel? =
        when (event.method) {
            "Network.requestWillBeSent" -> applyRequestWillBeSent(event.params, session)
            "Network.responseReceived" -> applyResponseReceived(event.params, session)
            "Network.loadingFinished" -> applyLoadingFinished(event.params, session)
            "Network.loadingFailed" -> applyLoadingFailed(event.params, session)
            else -> null
        }

    private fun applyRequestWillBeSent(params: Any?, session: SessionInfo): NetworkRequestModel? {
        if (params !is Map<*, *>) return null
        val requestId = params["requestId"] as? String ?: return null
        val request = params["request"] as? Map<*, *>
        val headers = parseHeaders(request?.get("headers"))
        val timestamp = parseTimestamp(params)
        val url = (params["documentURL"] as? String) ?: (request?.get("url") as? String)
        val method = request?.get("method") as? String
        val postData = parsePostData(request)
        val redirectResponse = params["redirectResponse"] as? Map<*, *>
        val redirectStatus = (redirectResponse?.get("status") as? Number)?.toInt()
        val redirectUrl = redirectResponse?.get("url") as? String

        return store.upsert(
            requestId = requestId,
            create = {
                NetworkRequestModel(
                    requestId = requestId,
                    stage = NetworkRequestStage.PENDING,
                    url = url,
                    method = method,
                    timestamp = timestamp,
                    resourceType = params["type"] as? String,
                    initiatorType = params["initiator"] as? String,
                    requestHeaders = headers,
                    postData = postData,
                    sessionId = session.sessionId,
                    targetId = session.targetId,
                    redirectStatus = redirectStatus,
                    redirectUrl = redirectUrl,
                )
            },
            transform = { existing ->
                // A terminal request is never reset by a duplicate/redirect
                // requestWillBeSent. Otherwise refresh the request metadata.
                if (existing.isTerminal) {
                    existing
                } else {
                    existing.copy(
                        url = url ?: existing.url,
                        method = method ?: existing.method,
                        requestHeaders = headers.ifEmpty { existing.requestHeaders },
                        resourceType = params["type"] as? String ?: existing.resourceType,
                        initiatorType = params["initiator"] as? String ?: existing.initiatorType,
                        redirectStatus = redirectStatus ?: existing.redirectStatus,
                        redirectUrl = redirectUrl ?: existing.url,
                    )
                }
            },
        )
    }

    private fun applyResponseReceived(params: Any?, session: SessionInfo): NetworkRequestModel? {
        if (params !is Map<*, *>) return null
        val requestId = params["requestId"] as? String ?: return null
        val response = params["response"] as? Map<*, *>
        val headers = parseHeaders(response?.get("headers"))
        val timing = ResourceTiming.fromParams(params["timing"])
        return store.upsert(
            requestId = requestId,
            create = {
                NetworkRequestModel(
                    requestId = requestId,
                    stage = NetworkRequestStage.RESPONSE_RECEIVED,
                    url = response?.get("url") as? String,
                    timestamp = parseTimestamp(params),
                    resourceType = params["type"] as? String,
                    responseStatus = (response?.get("status") as? Number)?.toInt(),
                    responseStatusText = response?.get("statusText") as? String,
                    responseMimeType = response?.get("mimeType") as? String,
                    responseHeaders = headers,
                    timing = timing,
                    sessionId = session.sessionId,
                    targetId = session.targetId,
                )
            },
            transform = { existing ->
                if (existing.isTerminal) {
                    existing
                } else {
                    existing.copy(
                        stage = NetworkRequestStage.RESPONSE_RECEIVED,
                        url = (response?.get("url") as? String) ?: existing.url,
                        resourceType = params["type"] as? String ?: existing.resourceType,
                        responseStatus = (response?.get("status") as? Number)?.toInt() ?: existing.responseStatus,
                        responseStatusText = response?.get("statusText") as? String ?: existing.responseStatusText,
                        responseMimeType = response?.get("mimeType") as? String ?: existing.responseMimeType,
                        responseHeaders = headers.ifEmpty { existing.responseHeaders },
                        timing = timing ?: existing.timing,
                    )
                }
            },
        )
    }

    private fun applyLoadingFinished(params: Any?, session: SessionInfo): NetworkRequestModel? =
        applyTerminal(params, session, NetworkRequestStage.FINISHED) { existing, _ ->
            existing.withStage(NetworkRequestStage.FINISHED)
        }

    private fun applyLoadingFailed(params: Any?, session: SessionInfo): NetworkRequestModel? =
        applyTerminal(params, session, NetworkRequestStage.FAILED) { existing, p ->
            existing.copy(
                stage = NetworkRequestStage.FAILED,
                loadingError = (p["error"] as? String) ?: (p["errorText"] as? String),
                canceled = (p["canceled"] as? Boolean) ?: false,
            )
        }

    private fun applyTerminal(
        params: Any?,
        session: SessionInfo,
        createStage: NetworkRequestStage,
        transition: (NetworkRequestModel, Map<*, *>) -> NetworkRequestModel,
    ): NetworkRequestModel? {
        if (params !is Map<*, *>) return null
        val requestId = params["requestId"] as? String ?: return null
        return store.upsert(
            requestId = requestId,
            create = {
                // A terminal event can arrive without a prior requestWillBeSent
                // (e.g. an aborted/redirected request); build a terminal entry
                // matching the arriving event and apply its transition fields.
                transition(
                    NetworkRequestModel(
                        requestId = requestId,
                        stage = createStage,
                        timestamp = parseTimestamp(params),
                        sessionId = session.sessionId,
                        targetId = session.targetId,
                    ),
                    params,
                )
            },
            transform = { existing -> transition(existing, params) },
        )
    }

    /** Parses the browser-provided CDP `time` value (a Number) verbatim. */
    private fun parseTimestamp(params: Map<*, *>): Double? =
        (params["time"] as? Number)?.toDouble()

    private fun parseHeaders(raw: Any?): Map<String, String> {
        if (raw !is Map<*, *>) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.forEach { (k, v) -> out[k.toString()] = v?.toString() ?: "" }
        return out
    }

    private fun parsePostData(request: Map<*, *>?): String? {
        val postData = request?.get("postData")
        if (postData is Map<*, *>) {
            return (postData as Map<*, *>)["text"] as? String
        }
        return postData as? String
    }
}