package com.coppermux.cutools.core.network

/**
 * Formats a [ResourceTiming] into a readable waterfall breakdown.
 *
 * Only stages for which Chrome reports both endpoints are included. A timing
 * with no usable stages renders as an explicit "unavailable" marker — CuTools
 * never fabricates timing stages.
 */
object ResourceTimingFormatter {

    /** Formats the timing into a short human-readable summary. */
    fun format(timing: ResourceTiming): String {
        val parts = mutableListOf<String>()
        timing.dnsDuration?.let { parts += "DNS: ${ms(it)}ms" }
        timing.connectionDuration?.let { parts += "Connection: ${ms(it)}ms" }
        timing.sslDuration?.let { parts += "SSL: ${ms(it)}ms" }
        timing.sendDuration?.let { parts += "Send: ${ms(it)}ms" }
        timing.ttfbDuration?.let { parts += "TTFB: ${ms(it)}ms" }
        timing.latencyDuration?.let { parts += "Latency: ${ms(it)}ms" }
        timing.contentDownloadDuration?.let { parts += "Download: ${ms(it)}ms" }
        if (parts.isEmpty()) return "Timing: unavailable"
        return "Timing: " + parts.joinToString(", ")
    }

    private fun ms(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format("%.1f", value)
}