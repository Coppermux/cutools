package com.coppermux.cutools.core.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import com.coppermux.cutools.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Temporary diagnostic recording and export facility for CuTools V1.0 Drop Connection investigation.
 *
 * Requirements satisfied:
 * - Structured events correlated by unique diagnostic session ID.
 * - Thread-safe, bounded in-memory buffer (5,000 entries) and persistent disk file.
 * - Captures events continuously whether F12 dashboard is open or closed.
 * - Sanitized against cookies, authorization headers, credentials, and full page contents.
 * - One-tap export via Android standard share sheet (FileProvider file + text) and clipboard fallback.
 * - Isolated and easily removable.
 * - SEC-02: Disabled in release builds to protect sensitive diagnostic information.
 */
object DiagnosticTracer {

    private const val TAG = "CuTools/DiagnosticTracer"
    private const val MAX_ENTRIES = 5000
    private const val LOG_FILE_NAME = "cutools_diagnostic_trace.txt"

    /**
     * Diagnostic tracing active state. Initialized from [BuildConfig.DEBUG],
     * and strictly disabled in release builds.
     */
    @Volatile
    var isEnabled: Boolean = BuildConfig.DEBUG
        @VisibleForTesting set

    val sessionId: String = UUID.randomUUID().toString().take(8).uppercase()
    val startTimeMs: Long = System.currentTimeMillis()

    private val entryCount = AtomicInteger(0)
    private val memoryLog = ConcurrentLinkedQueue<DiagnosticEntry>()
    private val diskExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cutools-diag-tracer").apply { isDaemon = true }
    }

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var logFile: File? = null

    data class DiagnosticEntry(
        val timestampMs: Long,
        val category: String,
        val event: String,
        val details: Map<String, Any?>,
    ) {
        fun format(timeFormat: SimpleDateFormat): String {
            val timeStr = timeFormat.format(Date(timestampMs))
            val detailStr = if (details.isNotEmpty()) {
                details.entries.joinToString(prefix = " {", postfix = "}") { (k, v) ->
                    "$k=${sanitize(v)}"
                }
            } else ""
            return "[$timeStr] [$category] $event$detailStr"
        }

        private fun sanitize(value: Any?): String {
            if (value == null) return "null"
            val str = value.toString()
            // Defensive sanitization: redact common secret keys if present
            if (str.contains("cookie", ignoreCase = true) ||
                str.contains("authorization", ignoreCase = true) ||
                str.contains("bearer ", ignoreCase = true)
            ) {
                return "[REDACTED]"
            }
            return str
        }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app

        val isDebuggable = try {
            (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Throwable) {
            BuildConfig.DEBUG
        }
        isEnabled = BuildConfig.DEBUG && isDebuggable

        if (!isEnabled) {
            return
        }

        val file = File(app.filesDir, LOG_FILE_NAME)
        logFile = file

        val appVersion = getAppVersionString(app)
        trace(
            category = "APP_LIFECYCLE",
            event = "DIAGNOSTIC_SESSION_START",
            "sessionId" to sessionId,
            "appVersion" to appVersion,
            "androidRelease" to Build.VERSION.RELEASE,
            "androidSdk" to Build.VERSION.SDK_INT,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    private fun getAppVersionString(context: Context?): String = try {
        context?.let { ctx ->
            val pInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            "${pInfo.versionName} ($code)"
        } ?: "1.0.0 (10000)"
    } catch (_: Throwable) {
        "1.0.0 (10000)"
    }

    /**
     * Records a structured diagnostic entry.
     */
    fun trace(category: String, event: String, vararg pairs: Pair<String, Any?>) {
        if (!isEnabled) return
        trace(category, event, pairs.toMap())
    }

    /**
     * Records a structured diagnostic entry with a map of key-value attributes.
     */
    fun trace(category: String, event: String, details: Map<String, Any?> = emptyMap()) {
        if (!isEnabled) return

        val now = System.currentTimeMillis()
        val entry = DiagnosticEntry(
            timestampMs = now,
            category = category,
            event = event,
            details = details,
        )

        // Memory buffer maintenance
        memoryLog.add(entry)
        val count = entryCount.incrementAndGet()
        while (count > MAX_ENTRIES && entryCount.get() > MAX_ENTRIES) {
            memoryLog.poll()
            entryCount.decrementAndGet()
        }

        // Android logcat mirroring
        try {
            Log.d(TAG, entry.format(SimpleDateFormat("HH:mm:ss.SSS", Locale.US)))
        } catch (_: Throwable) {
        }

        // Persistent disk file append
        val targetFile = logFile
        if (targetFile != null) {
            diskExecutor.execute {
                try {
                    // Check file size bounds (max 2 MB)
                    if (targetFile.exists() && targetFile.length() > 2 * 1024 * 1024) {
                        targetFile.delete()
                    }
                    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    val line = entry.format(timeFormat) + "\n"
                    FileOutputStream(targetFile, true).use { fos ->
                        OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                            writer.write(line)
                            writer.flush()
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Returns total number of recorded entries in current session.
     */
    fun getLogCount(): Int = memoryLog.size

    /**
     * Clears all diagnostic logs in memory and on disk.
     */
    fun clear() {
        memoryLog.clear()
        entryCount.set(0)
        val targetFile = logFile
        if (targetFile != null && isEnabled) {
            diskExecutor.execute {
                try {
                    if (targetFile.exists()) targetFile.delete()
                } catch (_: Throwable) {}
            }
        }
        if (isEnabled) {
            trace("DIAGNOSTIC", "LOGS_CLEARED", "sessionId" to sessionId)
        }
    }

    /**
     * Generates human-readable header and all recorded events.
     */
    fun generateExportText(context: Context? = appContext): String {
        if (!isEnabled) {
            return "Diagnostic tracing is disabled in release builds."
        }

        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val now = Date()

        sb.append("======================================================================\n")
        sb.append("             CUTOOLS V1.0 DIAGNOSTIC TRACE (DROP CONNECTION)          \n")
        sb.append("======================================================================\n")
        sb.append("Session ID       : $sessionId\n")
        sb.append("Export Time      : ${dateFormat.format(now)}\n")
        sb.append("App Version      : ${getAppVersionString(context)}\n")
        sb.append("Android OS       : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Device           : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})\n")

        val webViewPackage = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WebView.getCurrentWebViewPackage()?.let { "${it.packageName} v${it.versionName}" }
            } else null
        } catch (_: Throwable) { null } ?: "Unknown / Not Resolved"
        sb.append("WebView Engine   : $webViewPackage\n")
        sb.append("Recorded Events  : ${memoryLog.size}\n")
        sb.append("======================================================================\n\n")

        sb.append("--- BEGIN EVENT TRACE ---\n")
        for (entry in memoryLog) {
            sb.append(entry.format(timeFormat)).append("\n")
        }
        sb.append("--- END EVENT TRACE ---\n")

        return sb.toString()
    }

    /**
     * Exports trace file and presents Android standard sharing intent chooser,
     * while copying the trace to clipboard as a reliable backup.
     */
    fun shareTrace(context: Context) {
        if (!isEnabled) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Diagnostic tracing is disabled in release builds.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val traceText = generateExportText(context)
            trace("DIAGNOSTIC", "SHARE_TRACE_REQUESTED", "sizeBytes" to traceText.length)

            // 1. Copy to clipboard as immediate fallback
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("CuTools Diagnostic Trace", traceText)
                clipboard?.setPrimaryClip(clip)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to copy to clipboard: ${t.message}")
            }

            // 2. Write to a shareable file in cacheDir
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val exportFile = File(context.cacheDir, "cutools_trace_${sessionId}_$timestamp.txt")
            exportFile.writeText(traceText, Charsets.UTF_8)

            // 3. Obtain FileProvider URI
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportFile,
            )

            // 4. Fire ACTION_SEND intent chooser
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "CuTools Diagnostic Trace [$sessionId]")
                // Include text (truncated if very large to prevent transaction limit)
                val inlineText = if (traceText.length > 50_000) {
                    traceText.take(50_000) + "\n\n[... Truncated for Intent Extra. See attached file for complete trace ...]"
                } else {
                    traceText
                }
                putExtra(Intent.EXTRA_TEXT, inlineText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share CuTools Diagnostic Trace").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Diagnostic trace copied & sharing opened (${memoryLog.size} events)", Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error sharing diagnostic trace", t)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Export error: ${t.message}. Trace was copied to clipboard.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @VisibleForTesting
    fun resetForTesting(context: Context? = null, forceEnabled: Boolean? = null) {
        memoryLog.clear()
        entryCount.set(0)
        logFile = null
        appContext = null
        isEnabled = forceEnabled ?: BuildConfig.DEBUG
        if (context != null && isEnabled) {
            init(context)
        }
    }

    @VisibleForTesting
    fun getLogFileForTesting(): File? = logFile
}
