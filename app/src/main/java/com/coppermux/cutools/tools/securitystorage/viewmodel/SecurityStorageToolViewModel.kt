package com.coppermux.cutools.tools.securitystorage.viewmodel

import com.coppermux.cutools.core.security.SecurityEngine
import com.coppermux.cutools.core.security.models.SecurityActionResult
import com.coppermux.cutools.core.security.models.SecurityError
import com.coppermux.cutools.core.storage.CacheStorageEngine
import com.coppermux.cutools.core.storage.IndexedDbEngine
import com.coppermux.cutools.core.storage.StorageEngine
import com.coppermux.cutools.core.storage.models.CacheEntryModel
import com.coppermux.cutools.core.storage.models.CacheStorageBucketModel
import com.coppermux.cutools.core.storage.models.IndexedDbDataEntriesModel
import com.coppermux.cutools.core.storage.models.IndexedDatabaseModel
import com.coppermux.cutools.core.storage.models.StorageActionResult
import com.coppermux.cutools.core.storage.models.StorageError
import com.coppermux.cutools.core.storage.models.StorageIntrospectionActionResult
import com.coppermux.cutools.core.storage.models.StorageIntrospectionError
import com.coppermux.cutools.core.storage.models.StorageTypeModel
import com.coppermux.cutools.core.storage.models.StorageUsageAndQuotaModel
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.tools.securitystorage.models.SecurityStorageTab
import com.coppermux.cutools.tools.securitystorage.models.SecurityStorageViewModelSnapshot

/**
 * Unified DevTools Security & Storage panel ViewModel.
 *
 * Orchestrates the four security / storage engines — [SecurityEngine],
 * [StorageEngine], [IndexedDbEngine], and [CacheStorageEngine] — into a single
 * thread-safe [state] snapshot. Target attach / detach / connection-loss actions
 * are fanned out to every engine in unison so their session-bound state clears
 * and refreshes together, and action dispatchers issue storage clearance,
 * object store reset, cache invalidation, and quota refresh commands while
 * propagating any resolved error back into the snapshot as a renderable
 * `...Error` string (No-Fake-Capability rule).
 *
 * Threading: the aggregated [state] is `@Volatile` and rebuilt non-blocking by
 * reading each engine's own atomic/volatile state plus this ViewModel's cached
 * introspection results, so concurrent event streams across engines never
 * produce a torn snapshot. No Chrome communication is performed here — all
 * commands are delegated to the shared engines.
 */
class SecurityStorageToolViewModel(
    private val security: SecurityEngine,
    private val storage: StorageEngine,
    private val indexedDb: IndexedDbEngine,
    private val cacheStorage: CacheStorageEngine,
) {

    /** The most recent aggregated snapshot of all security/storage subsystems. */
    @Volatile
    var state: SecurityStorageViewModelSnapshot = SecurityStorageViewModelSnapshot.empty()
        private set

    /** The active attached target session, or null when detached. */
    @Volatile
    var attachedSession: SessionInfo? = null
        private set

    val isAttached: Boolean get() = attachedSession != null

    /** The active [SecurityStorageTab] tab. */
    val tab: SecurityStorageTab
        get() = state.tab

    /** Renderable error strings resolved by the last dispatched actions, if any. */
    @Volatile
    private var securityError: String? = null
    @Volatile
    private var storageError: String? = null
    @Volatile
    private var indexedDbError: String? = null
    @Volatile
    private var cacheError: String? = null

    // Cached genuine introspection query results (the IndexedDB / CacheStorage
    // engines do not retain results themselves).
    @Volatile
    private var databaseNames: List<String> = emptyList()
    @Volatile
    private var databaseSchema: IndexedDatabaseModel? = null
    @Volatile
    private var dataEntries: IndexedDbDataEntriesModel? = null
    @Volatile
    private var cacheBuckets: List<CacheStorageBucketModel> = emptyList()
    @Volatile
    private var cacheEntries: List<CacheEntryModel> = emptyList()

    // ================================================================
    // Target lifecycle
    // ================================================================

    /** Attaches every security/storage engine to a newly-attached target session. */
    fun attachTarget(session: SessionInfo) {
        security.bindSession(session)
        storage.bindSession(session)
        indexedDb.bindSession(session)
        cacheStorage.bindSession(session)
        this.attachedSession = session
        resetQueryCache()
        refresh()
    }

    /** Detaches every security/storage engine, clearing all target-scoped state. */
    fun detachTarget() {
        security.detach()
        storage.detach()
        indexedDb.detach()
        cacheStorage.detach()
        attachedSession = null
        resetQueryCache()
        refresh()
    }

    /** Handles connection loss across all security/storage engines. */
    fun onConnectionLost() {
        security.onConnectionLost()
        storage.onConnectionLost()
        indexedDb.onConnectionLost()
        cacheStorage.onConnectionLost()
        attachedSession = null
        resetQueryCache()
        refresh()
    }

    /** Releases event subscriptions across all event-driven engines. */
    fun shutdown() {
        security.shutdown()
        storage.shutdown()
    }

    // ================================================================
    // Presentation tab
    // ================================================================

    /** Selects the active [SecurityStorageTab] for the UI. */
    fun selectTab(tab: SecurityStorageTab) {
        state = state.copy(tab = tab)
    }

    // ================================================================
    // Aggregation
    // ================================================================

    /**
     * Rebuilds the aggregated [state] snapshot from each engine's current state.
     * Each read is non-blocking (each engine field is atomic/volatile).
     */
    fun refresh() {
        val active = attachedSession
        state = SecurityStorageViewModelSnapshot(
            sessionId = active?.sessionId,
            targetId = active?.targetId,
            attached = active != null,
            tab = state.tab,
            securityState = security.securityState,
            schemeIsCryptographic = security.schemeIsCryptographic,
            securitySummary = security.summary,
            explanations = security.explanations,
            certificateDetails = security.certificateDetails,
            certificateErrors = security.certificateErrors,
            securityCapability = security.capability,
            securityEnabled = security.enabled,
            securityError = securityError,
            quotaUsage = storage.latestUsage,
            trackedOrigins = storage.trackedOrigins,
            cacheStorageContentUpdates = storage.cacheStorageContentUpdates,
            cacheStorageListUpdates = storage.cacheStorageListUpdates,
            storageCapability = storage.capability,
            storageError = storageError,
            databaseNames = databaseNames,
            databaseSchema = databaseSchema,
            dataEntries = dataEntries,
            indexedDbCapability = indexedDb.capability,
            indexedDbError = indexedDbError,
            cacheBuckets = cacheBuckets,
            cacheEntries = cacheEntries,
            cacheCapability = cacheStorage.capability,
            cacheError = cacheError,
        )
    }

    // ================================================================
    // Action dispatchers — Security
    // ================================================================

    /** Enables `Security` domain notifications on the attached target. */
    fun enableSecurity(callback: (SecurityActionResult<Unit>) -> Unit) {
        security.enable { result ->
            securityError = if (result is SecurityActionResult.Failure) {
                describeSecurityError((result as SecurityActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Disables `Security` domain notifications on the attached target. */
    fun disableSecurity(callback: (SecurityActionResult<Unit>) -> Unit) {
        security.disable { result ->
            securityError = if (result is SecurityActionResult.Failure) {
                describeSecurityError((result as SecurityActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Configures whether the browser ignores certificate errors. */
    fun setIgnoreCertificateErrors(
        ignore: Boolean,
        callback: (SecurityActionResult<Unit>) -> Unit,
    ) {
        security.setIgnoreCertificateErrors(ignore) { result ->
            securityError = if (result is SecurityActionResult.Failure) {
                describeSecurityError((result as SecurityActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    // ================================================================
    // Action dispatchers — Storage quota & data clearance
    // ================================================================

    /** Refreshes the origin usage/quota snapshot via `Storage.getUsageAndQuota`. */
    fun refreshQuota(
        origin: String,
        callback: (StorageActionResult<StorageUsageAndQuotaModel>) -> Unit,
    ) {
        storage.getUsageAndQuota(origin) { result ->
            storageError = if (result is StorageActionResult.Failure) {
                describeStorageError((result as StorageActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Clears the requested [StorageTypeModel]s for an origin. */
    fun clearDataForOrigin(
        origin: String,
        storageTypes: List<StorageTypeModel>,
        callback: (StorageActionResult<Unit>) -> Unit,
    ) {
        storage.clearDataForOrigin(origin, storageTypes) { result ->
            storageError = if (result is StorageActionResult.Failure) {
                describeStorageError((result as StorageActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Begins tracking an origin's cache storage updates. */
    fun trackCacheStorageForOrigin(
        origin: String,
        scriptURL: String,
        callback: (StorageActionResult<Unit>) -> Unit,
    ) {
        storage.trackCacheStorageForOrigin(origin, scriptURL) { result ->
            storageError = if (result is StorageActionResult.Failure) {
                describeStorageError((result as StorageActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Stops tracking an origin's cache storage updates. */
    fun untrackCacheStorageForOrigin(origin: String, callback: (StorageActionResult<Unit>) -> Unit) {
        storage.untrackCacheStorageForOrigin(origin) { result ->
            storageError = if (result is StorageActionResult.Failure) {
                describeStorageError((result as StorageActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    // ================================================================
    // Action dispatchers — IndexedDB
    // ================================================================

    /** Fetches the IndexedDB database names for an origin and caches them. */
    fun requestDatabaseNames(
        origin: String,
        callback: (StorageIntrospectionActionResult<List<String>>) -> Unit,
    ) {
        indexedDb.requestDatabaseNames(origin) { result ->
            databaseNames = if (result is StorageIntrospectionActionResult.Success) {
                (result as StorageIntrospectionActionResult.Success).value
            } else {
                emptyList()
            }
            indexedDbError = if (result is StorageIntrospectionActionResult.Failure) {
                describeIntrospectionError((result as StorageIntrospectionActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Fetches and caches the schema of a single IndexedDB database. */
    fun requestDatabase(
        origin: String,
        databaseName: String,
        callback: (StorageIntrospectionActionResult<IndexedDatabaseModel>) -> Unit,
    ) {
        indexedDb.requestDatabase(origin, databaseName) { result ->
            databaseSchema = if (result is StorageIntrospectionActionResult.Success) {
                (result as StorageIntrospectionActionResult.Success).value
            } else {
                null
            }
            indexedDbError = if (result is StorageIntrospectionActionResult.Failure) {
                describeIntrospectionError((result as StorageIntrospectionActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Fetches and caches a key-range page of object store entries. */
    fun requestData(
        origin: String,
        databaseName: String,
        objectStoreName: String,
        skipCount: Int,
        pageSize: Int,
        callback: (StorageIntrospectionActionResult<IndexedDbDataEntriesModel>) -> Unit,
    ) {
        indexedDb.requestData(origin, databaseName, objectStoreName, skipCount, pageSize) { result ->
            dataEntries = if (result is StorageIntrospectionActionResult.Success) {
                (result as StorageIntrospectionActionResult.Success).value
            } else {
                null
            }
            indexedDbError = if (result is StorageIntrospectionActionResult.Failure) {
                describeIntrospectionError((result as StorageIntrospectionActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Clears all entries from an object store (a storage reset command). */
    fun clearObjectStore(
        origin: String,
        databaseName: String,
        objectStoreName: String,
        callback: (StorageIntrospectionActionResult<Unit>) -> Unit,
    ) {
        indexedDb.clearObjectStore(origin, databaseName, objectStoreName) { result ->
            indexedDbError = if (result is StorageIntrospectionActionResult.Failure) {
                describeIntrospectionError((result as StorageIntrospectionActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    // ================================================================
    // Action dispatchers — CacheStorage
    // ================================================================

    /** Fetches and caches the cache storage buckets for an origin. */
    fun requestCacheNames(
        origin: String,
        callback: (StorageIntrospectionActionResult<List<CacheStorageBucketModel>>) -> Unit,
    ) {
        cacheStorage.requestCacheNames(origin) { result ->
            cacheBuckets = if (result is StorageIntrospectionActionResult.Success) {
                (result as StorageIntrospectionActionResult.Success).value
            } else {
                emptyList()
            }
            cacheError = if (result is StorageIntrospectionActionResult.Failure) {
                describeIntrospectionError((result as StorageIntrospectionActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Fetches and caches a page of cached request/response entries. */
    fun requestCacheEntries(
        cacheId: String,
        skipCount: Int,
        pageSize: Int,
        callback: (StorageIntrospectionActionResult<List<CacheEntryModel>>) -> Unit,
    ) {
        cacheStorage.requestEntries(cacheId, skipCount, pageSize) { result ->
            cacheEntries = if (result is StorageIntrospectionActionResult.Success) {
                (result as StorageIntrospectionActionResult.Success).value
            } else {
                emptyList()
            }
            cacheError = if (result is StorageIntrospectionActionResult.Failure) {
                describeIntrospectionError((result as StorageIntrospectionActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Deletes an entire cache bucket (cache clearance command). */
    fun deleteCache(cacheId: String, callback: (StorageIntrospectionActionResult<Unit>) -> Unit) {
        cacheStorage.deleteCache(cacheId) { result ->
            cacheError = if (result is StorageIntrospectionActionResult.Failure) {
                describeIntrospectionError((result as StorageIntrospectionActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    /** Deletes a single cached entry from a cache bucket. */
    fun deleteCacheEntry(
        cacheId: String,
        requestUrl: String,
        callback: (StorageIntrospectionActionResult<Unit>) -> Unit,
    ) {
        cacheStorage.deleteEntry(cacheId, requestUrl) { result ->
            cacheError = if (result is StorageIntrospectionActionResult.Failure) {
                describeIntrospectionError((result as StorageIntrospectionActionResult.Failure).error)
            } else {
                null
            }
            refresh()
            callback(result)
        }
    }

    // ================================================================
    // Error description helpers
    // ================================================================

    private fun describeSecurityError(error: SecurityError): String = when (error) {
        is SecurityError.Unsupported -> "Unsupported: ${error.reason}"
        is SecurityError.Unavailable -> "Unavailable: ${error.detail}"
        is SecurityError.ExecutionFailed -> "Failed: ${error.cause.message ?: "execution failed"}"
        is SecurityError.TargetDisconnected -> "Failed: target disconnected"
    }

    private fun describeStorageError(error: StorageError): String = when (error) {
        is StorageError.Unsupported -> "Unsupported: ${error.reason}"
        is StorageError.Unavailable -> "Unavailable: ${error.detail}"
        is StorageError.ExecutionFailed -> "Failed: ${error.cause.message ?: "execution failed"}"
        is StorageError.TargetDisconnected -> "Failed: target disconnected"
    }

    private fun describeIntrospectionError(error: StorageIntrospectionError): String = when (error) {
        is StorageIntrospectionError.Unsupported -> "Unsupported: ${error.reason}"
        is StorageIntrospectionError.Unavailable -> "Unavailable: ${error.detail}"
        is StorageIntrospectionError.ExecutionFailed -> "Failed: ${error.cause.message ?: "execution failed"}"
        is StorageIntrospectionError.TargetDisconnected -> "Failed: target disconnected"
    }

    private fun resetQueryCache() {
        databaseNames = emptyList()
        databaseSchema = null
        dataEntries = null
        cacheBuckets = emptyList()
        cacheEntries = emptyList()
    }
}