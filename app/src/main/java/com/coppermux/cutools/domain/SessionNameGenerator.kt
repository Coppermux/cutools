package com.coppermux.cutools.domain

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionNameGenerator {

    fun generateName(url: String, timestamp: Long): String {
        val domain = extractDomain(url)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        return "$domain · $timeStr"
    }

    fun extractDomain(url: String): String {
        val host = try {
            val parsed = URI(url)
            parsed.host ?: ""
        } catch (e: Exception) {
            ""
        }
        if (host.isNotEmpty()) {
            return host.removePrefix("www.")
        }
        return url
    }
}