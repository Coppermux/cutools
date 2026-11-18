package com.coppermux.cutools.ui.homescreen

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formats a millisecond timestamp as a short human-relative label. */
fun relativeTime(timestamp: Long): String {
    val diffSec = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        diffSec < 60 -> "Just now"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        diffSec < 604800 -> "${diffSec / 86400}d ago"
        else -> SimpleDateFormat("dd MMM", Locale.US).format(Date(timestamp))
    }
}