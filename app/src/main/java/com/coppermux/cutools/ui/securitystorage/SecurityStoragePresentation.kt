package com.coppermux.cutools.ui.securitystorage

import com.coppermux.cutools.core.security.models.SecurityStateModel
import com.coppermux.cutools.core.storage.models.IndexedDbDataEntryModel
import com.coppermux.cutools.core.storage.models.IndexedObjectStoreIndexModel
import com.coppermux.cutools.core.storage.models.IndexedObjectStoreModel
import com.coppermux.cutools.tools.securitystorage.models.SecurityStorageTab
import com.coppermux.cutools.tools.securitystorage.models.SecurityStorageViewModelSnapshot
import java.time.Instant

/**
 * A single renderable row in the Security & Storage tool's active-tab list.
 *
 * Rows are pure presentation — derived deterministically from the
 * [SecurityStorageViewModelSnapshot] by [SecurityStoragePresentation], never
 * from raw CDP. [label] is the primary text, [detail] the secondary value, and
 * [caption] an optional trailing annotation.
 */
data class SecurityStorageRow(
    val label: String,
    val detail: String = "",
    val caption: String = "",
)

/**
 * Pure, testable presentation layer for the Security & Storage tool.
 *
 * Maps a [SecurityStorageViewModelSnapshot] to the renderable surface: a status
 * summary line, an `UNSUPPORTED` / `UNAVAILABLE` / `FAILED` banner (derived
 * solely from engine-resolved errors), and the content rows for the active
 * [SecurityStorageTab] tab. All formatting is deterministic so the UI renders
 * the exact same text as any test would assert — no browser truth is owned
 * here.
 */
object SecurityStoragePresentation {

    /** A single-line status summary for the tool header. */
    fun statusText(snapshot: SecurityStorageViewModelSnapshot): String = buildString {
        if (!snapshot.attached) {
            append("Not attached to a target")
            return@buildString
        }
        append("Attached")
        when (snapshot.tab) {
            SecurityStorageTab.SECURITY -> append(" · Security ").append(securityLabel(snapshot.securityState))
            SecurityStorageTab.STORAGE_QUOTA -> append(" · Storage Quota")
            SecurityStorageTab.INDEXED_DB -> append(" · IndexedDB")
            SecurityStorageTab.CACHE_STORAGE -> append(" · Cache Storage")
        }
    }

    /**
     * The banner text to show for the active tab, or null when no engine error
     * is present. The error strings resolved by the ViewModel already carry the
     * `Unsupported:` / `Unavailable:` / `Failed:` status prefix, so this is a
     * pure pass-through of genuine protocol state — never fabricated.
     */
    fun banner(snapshot: SecurityStorageViewModelSnapshot): String? = when (snapshot.tab) {
        SecurityStorageTab.SECURITY -> snapshot.securityError
        SecurityStorageTab.STORAGE_QUOTA -> snapshot.storageError
        SecurityStorageTab.INDEXED_DB -> snapshot.indexedDbError
        SecurityStorageTab.CACHE_STORAGE -> snapshot.cacheError
    }

    /** The content rows for the active [SecurityStorageTab] tab. */
    fun rows(snapshot: SecurityStorageViewModelSnapshot): List<SecurityStorageRow> = when (snapshot.tab) {
        SecurityStorageTab.SECURITY -> securityRows(snapshot)
        SecurityStorageTab.STORAGE_QUOTA -> storageQuotaRows(snapshot)
        SecurityStorageTab.INDEXED_DB -> indexedDbRows(snapshot)
        SecurityStorageTab.CACHE_STORAGE -> cacheStorageRows(snapshot)
    }

    // ================================================================
    // Security
    // ================================================================

    private fun securityRows(snapshot: SecurityStorageViewModelSnapshot): List<SecurityStorageRow> {
        val out = ArrayList<SecurityStorageRow>()
        out += SecurityStorageRow(
            label = "Security state",
            detail = securityLabel(snapshot.securityState),
            caption = if (snapshot.schemeIsCryptographic) "cryptographic" else "plaintext",
        )
        snapshot.securitySummary?.let { out += SecurityStorageRow(label = "Summary", detail = it) }

        val cert = snapshot.certificateDetails
        if (cert != null) {
            out += SecurityStorageRow(label = "Certificate subject", detail = cert.subjectName ?: "—")
            out += SecurityStorageRow(label = "Certificate issuer", detail = cert.issuer ?: "—")
            if (cert.protocol.isNotEmpty()) out += SecurityStorageRow(label = "Protocol", detail = cert.protocol)
            if (cert.cipher.isNotEmpty()) out += SecurityStorageRow(label = "Cipher", detail = cert.cipher)
            if (cert.keyExchange.isNotEmpty()) out += SecurityStorageRow(label = "Key exchange", detail = cert.keyExchange)
            if (cert.validFrom > 0.0 || cert.validTo > 0.0) {
                out += SecurityStorageRow(
                    label = "Validity",
                    detail = "${epochSeconds(cert.validFrom)} → ${epochSeconds(cert.validTo)}",
                )
            }
            cert.certificate.forEachIndexed { i, pem ->
                out += SecurityStorageRow(label = "Certificate #${i + 1}", detail = pem, caption = "chain")
            }
        }

        snapshot.explanations.forEach { explanation ->
            out += SecurityStorageRow(
                label = explanation.title ?: "Explanation",
                detail = explanation.summary,
                caption = securityLabel(explanation.securityState),
            )
            explanation.recommendations.forEach {
                out += SecurityStorageRow(label = "Recommendation", detail = it)
            }
        }

        snapshot.certificateErrors.forEach { error ->
            out += SecurityStorageRow(
                label = "Certificate error",
                detail = error.errorType,
                caption = error.requestURL,
            )
        }

        if (out.isEmpty()) {
            out += SecurityStorageRow(label = "No security state reported")
        }
        return out
    }

    // ================================================================
    // Storage quota
    // ================================================================

    private fun storageQuotaRows(snapshot: SecurityStorageViewModelSnapshot): List<SecurityStorageRow> {
        val out = ArrayList<SecurityStorageRow>()
        val usage = snapshot.quotaUsage
        if (usage != null) {
            val percent = if (usage.quota > 0) (usage.usage.toDouble() / usage.quota * 100.0) else 0.0
            out += SecurityStorageRow(
                label = "Origin usage",
                detail = "${formatBytes(usage.usage.toDouble())} of ${formatBytes(usage.quota.toDouble())}",
                caption = "${trim(Math.round(percent * 10.0) / 10.0)}%",
            )
            usage.usageBreakdown.forEach { breakdown ->
                val type = breakdown.storageType?.wireValue ?: "unknown"
                out += SecurityStorageRow(
                    label = type,
                    detail = formatBytes(breakdown.usageBytes.toDouble()),
                    caption = "breakdown",
                )
            }
        } else {
            out += SecurityStorageRow(label = "No quota data loaded")
        }
        snapshot.trackedOrigins.forEach {
            out += SecurityStorageRow(label = "Tracking origin", detail = it)
        }
        snapshot.cacheStorageContentUpdates.forEach { update ->
            out += SecurityStorageRow(
                label = "Cache content updated",
                detail = update.origin,
                caption = update.key.id,
            )
        }
        snapshot.cacheStorageListUpdates.forEach { update ->
            out += SecurityStorageRow(label = "Cache list updated", detail = update.origin)
        }
        return out
    }

    // ================================================================
    // IndexedDB
    // ================================================================

    private fun indexedDbRows(snapshot: SecurityStorageViewModelSnapshot): List<SecurityStorageRow> {
        val out = ArrayList<SecurityStorageRow>()
        if (snapshot.databaseNames.isEmpty() && snapshot.databaseSchema == null && snapshot.dataEntries == null) {
            out += SecurityStorageRow(label = "No databases loaded")
            return out
        }
        snapshot.databaseNames.forEach {
            out += SecurityStorageRow(label = "Database", detail = it)
        }
        val schema = snapshot.databaseSchema
        if (schema != null) {
            out += SecurityStorageRow(label = "Database name", detail = schema.name, caption = "v${schema.version}")
            schema.objectStores.forEach { store -> appendObjectStore(store, out) }
        }
        snapshot.dataEntries?.entries?.forEach { entry -> appendDataEntry(entry, out) }
        return out
    }

    private fun appendObjectStore(store: IndexedObjectStoreModel, out: MutableList<SecurityStorageRow>) {
        out += SecurityStorageRow(
            label = "Object store ${store.name}",
            detail = "keyPath: ${store.keyPath}",
            caption = if (store.autoIncrement) "autoIncrement" else "",
        )
        store.indexes.forEach { index -> appendIndex(index, out) }
    }

    private fun appendIndex(index: IndexedObjectStoreIndexModel, out: MutableList<SecurityStorageRow>) {
        out += SecurityStorageRow(
            label = "  index ${index.name}",
            detail = "keyPath: ${index.keyPath}",
            caption = flags(index.unique, index.multiEntry),
        )
    }

    private fun appendDataEntry(entry: IndexedDbDataEntryModel, out: MutableList<SecurityStorageRow>) {
        out += SecurityStorageRow(
            label = "Entry",
            detail = entry.key,
            caption = entry.value,
        )
    }

    // ================================================================
    // CacheStorage
    // ================================================================

    private fun cacheStorageRows(snapshot: SecurityStorageViewModelSnapshot): List<SecurityStorageRow> {
        val out = ArrayList<SecurityStorageRow>()
        if (snapshot.cacheBuckets.isEmpty() && snapshot.cacheEntries.isEmpty()) {
            out += SecurityStorageRow(label = "No cache buckets loaded")
            return out
        }
        snapshot.cacheBuckets.forEach { bucket ->
            out += SecurityStorageRow(
                label = "Cache bucket ${bucket.cacheName}",
                detail = bucket.cacheId,
                caption = bucket.securityOrigin,
            )
        }
        snapshot.cacheEntries.forEach { entry ->
            out += SecurityStorageRow(
                label = entry.requestURL,
                detail = "${entry.responseStatus} ${entry.responseStatusText}",
                caption = "entries",
            )
        }
        return out
    }

    // ================================================================
    // Labels & formatters
    // ================================================================

    private fun securityLabel(state: SecurityStateModel?): String = when (state) {
        null -> "unknown"
        SecurityStateModel.UNKNOWN -> "unknown"
        SecurityStateModel.NEUTRAL -> "neutral"
        SecurityStateModel.INSECURE -> "insecure"
        SecurityStateModel.SECURE -> "secure"
        SecurityStateModel.INFO -> "info"
    }

    private fun flags(unique: Boolean, multiEntry: Boolean): String = buildString {
        if (unique) append("unique ")
        if (multiEntry) append("multiEntry ")
        if (isEmpty()) append("")
    }

    /** Formats a byte count as a human-readable string. */
    fun formatBytes(bytes: Double): String = when {
        bytes >= 1_048_576.0 -> "${trim(Math.round(bytes / 1_048_576.0 * 10.0) / 10.0)} MB"
        bytes >= 1024.0 -> "${trim(Math.round(bytes / 1024.0 * 10.0) / 10.0)} KB"
        else -> "${Math.round(bytes)} B"
    }

    /** Formats a CDP `TimeSinceEpoch` seconds value as an ISO UTC date. */
    fun epochSeconds(seconds: Double): String =
        if (seconds <= 0.0) "—" else Instant.ofEpochSecond(seconds.toLong()).toString()

    /** Renders [value] as a plain integer when whole, e.g. `1.0` → `1`. */
    private fun trim(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}