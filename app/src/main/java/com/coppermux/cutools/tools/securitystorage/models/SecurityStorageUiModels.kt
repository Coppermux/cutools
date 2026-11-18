package com.coppermux.cutools.tools.securitystorage.models

import com.coppermux.cutools.core.security.models.CertificateDetailsModel
import com.coppermux.cutools.core.security.models.CertificateErrorModel
import com.coppermux.cutools.core.security.models.SecurityCapabilityStatus
import com.coppermux.cutools.core.security.models.SecurityExplanationModel
import com.coppermux.cutools.core.security.models.SecurityStateModel
import com.coppermux.cutools.core.storage.models.CacheEntryModel
import com.coppermux.cutools.core.storage.models.CacheStorageBucketModel
import com.coppermux.cutools.core.storage.models.CacheStorageContentUpdatedModel
import com.coppermux.cutools.core.storage.models.CacheStorageListUpdatedModel
import com.coppermux.cutools.core.storage.models.IndexedDbDataEntriesModel
import com.coppermux.cutools.core.storage.models.IndexedDatabaseModel
import com.coppermux.cutools.core.storage.models.StorageCapabilityStatus
import com.coppermux.cutools.core.storage.models.StorageIntrospectionCapabilityStatus
import com.coppermux.cutools.core.storage.models.StorageUsageAndQuotaModel

/**
 * The active tab of the Security & Storage tool.
 *
 * Each value selects the sub-view the
 * [com.coppermux.cutools.tools.securitystorage.viewmodel.SecurityStorageToolViewModel]
 * presents: real-time TLS security state, origin storage quota, IndexedDB
 * introspection, or browser cache storage inspection. It is presentation state
 * only — the underlying security/storage engines run independently of which tab
 * is selected.
 */
enum class SecurityStorageTab {
    /** TLS security state, certificate chain details, and security explanations. */
    SECURITY,

    /** Origin usage vs quota with a per-storage-type breakdown. */
    STORAGE_QUOTA,

    /** IndexedDB database / object store schema browser and entry tables. */
    INDEXED_DB,

    /** Browser cache storage bucket selector and request/response pair tables. */
    CACHE_STORAGE,
}

/**
 * Immutable UI snapshot aggregating the active state of every security and
 * storage engine — [com.coppermux.cutools.core.security.SecurityEngine],
 * [com.coppermux.cutools.core.storage.StorageEngine],
 * [com.coppermux.cutools.core.storage.IndexedDbEngine], and
 * [com.coppermux.cutools.core.storage.CacheStorageEngine] — for the currently
 * attached target.
 *
 * Each engine's genuine state is surfaced as-is (never fabricated — the
 * No-Fake-Capability rule). [sessionId] / [targetId] / [attached] describe the
 * active target; [tab] selects the active tool tab. [databaseNames] /
 * [databaseSchema] / [dataEntries] and [cacheBuckets] / [cacheEntries] cache the
 * most recent genuine introspection query results dispatched through the
 * ViewModel (the IndexedDB / CacheStorage engines do not retain results
 * themselves). When an engine resolves a protocol failure, the corresponding
 * `...Error` field carries a human-readable string so the UI can surface
 * `UNSUPPORTED` / `UNAVAILABLE` / `FAILED` banners without interpreting raw
 * protocol state.
 */
data class SecurityStorageViewModelSnapshot(
    val sessionId: String? = null,
    val targetId: String? = null,
    val attached: Boolean = false,
    val tab: SecurityStorageTab = SecurityStorageTab.SECURITY,

    // Security engine
    val securityState: SecurityStateModel? = null,
    val schemeIsCryptographic: Boolean = false,
    val securitySummary: String? = null,
    val explanations: List<SecurityExplanationModel> = emptyList(),
    val certificateDetails: CertificateDetailsModel? = null,
    val certificateErrors: List<CertificateErrorModel> = emptyList(),
    val securityCapability: SecurityCapabilityStatus? = null,
    val securityEnabled: Boolean = false,
    val securityError: String? = null,

    // Storage quota engine
    val quotaUsage: StorageUsageAndQuotaModel? = null,
    val trackedOrigins: Set<String> = emptySet(),
    val cacheStorageContentUpdates: List<CacheStorageContentUpdatedModel> = emptyList(),
    val cacheStorageListUpdates: List<CacheStorageListUpdatedModel> = emptyList(),
    val storageCapability: StorageCapabilityStatus? = null,
    val storageError: String? = null,

    // IndexedDB engine
    val databaseNames: List<String> = emptyList(),
    val databaseSchema: IndexedDatabaseModel? = null,
    val dataEntries: IndexedDbDataEntriesModel? = null,
    val indexedDbCapability: StorageIntrospectionCapabilityStatus? = null,
    val indexedDbError: String? = null,

    // CacheStorage engine
    val cacheBuckets: List<CacheStorageBucketModel> = emptyList(),
    val cacheEntries: List<CacheEntryModel> = emptyList(),
    val cacheCapability: StorageIntrospectionCapabilityStatus? = null,
    val cacheError: String? = null,
) {
    companion object {
        fun empty(): SecurityStorageViewModelSnapshot = SecurityStorageViewModelSnapshot()
    }
}