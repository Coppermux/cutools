package com.coppermux.cutools.core.network

/**
 * Detailed per-request network timing as reported by Chrome's CDP
 * `ResourceTiming` payload (carried on `Network.responseReceived` -> `timing`).
 *
 * All raw timestamps are milliseconds relative to the resource's load start,
 * as supplied by Chrome. CuTools preserves them verbatim and only derives a
 * stage duration when both endpoints are present — timing stages are never
 * fabricated or invented.
 */
data class ResourceTiming(
    val dnsStart: Double? = null,
    val dnsEnd: Double? = null,
    val connectStart: Double? = null,
    val connectEnd: Double? = null,
    val sslStart: Double? = null,
    val sslEnd: Double? = null,
    val sendStart: Double? = null,
    val sendEnd: Double? = null,
    val receiveHeadersEnd: Double? = null,
    val responseEnd: Double? = null,
) {
    /** DNS resolution duration (`dnsEnd - dnsStart`), or null when incomplete. */
    val dnsDuration: Double? get() = duration(dnsStart, dnsEnd)

    /** TCP/TLS connection setup duration (`connectEnd - connectStart`). */
    val connectionDuration: Double? get() = duration(connectStart, connectEnd)

    /** TLS handshake duration (`sslEnd - sslStart`). */
    val sslDuration: Double? get() = duration(sslStart, sslEnd)

    /** Request send duration (`sendEnd - sendStart`). */
    val sendDuration: Double? get() = duration(sendStart, sendEnd)

    /** Time to first byte: request send start to first response headers. */
    val ttfbDuration: Double? get() = delta(sendStart, receiveHeadersEnd)

    /** End-to-end latency to first byte, measured from DNS start. */
    val latencyDuration: Double? get() = delta(dnsStart, receiveHeadersEnd)

    /** Response body download duration after headers arrive. */
    val contentDownloadDuration: Double? get() = delta(receiveHeadersEnd, responseEnd)

    companion object {
        /**
         * Parses a CDP `ResourceTiming` payload (a map) into a [ResourceTiming],
         * or null when absent / not a map. Only numeric timestamps are read.
         */
        fun fromParams(raw: Any?): ResourceTiming? {
            if (raw !is Map<*, *>) return null
            fun read(key: String): Double? = (raw[key] as? Number)?.toDouble()
            val parsed = ResourceTiming(
                dnsStart = read("dnsStart"),
                dnsEnd = read("dnsEnd"),
                connectStart = read("connectStart"),
                connectEnd = read("connectEnd"),
                sslStart = read("sslStart"),
                sslEnd = read("sslEnd"),
                sendStart = read("sendStart"),
                sendEnd = read("sendEnd"),
                receiveHeadersEnd = read("receiveHeadersEnd"),
                responseEnd = read("responseEnd"),
            )
            val hasAny = parsed.dnsStart != null || parsed.connectStart != null ||
                parsed.sslStart != null || parsed.sendStart != null ||
                parsed.receiveHeadersEnd != null || parsed.responseEnd != null
            return if (hasAny) parsed else null
        }

        private fun duration(start: Double?, end: Double?): Double? =
            if (start != null && end != null) end - start else null

        private fun delta(from: Double?, to: Double?): Double? =
            if (from != null && to != null) to - from else null
    }
}