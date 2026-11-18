package com.coppermux.cutools.tools.application.viewmodel

import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.tools.application.engine.CacheStorageEngine
import com.coppermux.cutools.tools.application.engine.CookieEngine
import com.coppermux.cutools.tools.application.engine.DOMStorageEngine
import com.coppermux.cutools.tools.application.engine.IndexedDbEngine
import com.coppermux.cutools.tools.application.engine.ServiceWorkerEngine
import com.coppermux.cutools.tools.application.engine.StorageEngine
import com.coppermux.cutools.tools.application.models.CacheStorageCapabilityStatus
import com.coppermux.cutools.tools.application.models.CookieCapabilityStatus
import com.coppermux.cutools.tools.application.models.CookieModel
import com.coppermux.cutools.tools.application.models.DOMStorageState
import com.coppermux.cutools.tools.application.models.IndexedDbCapabilityStatus
import com.coppermux.cutools.tools.application.models.ServiceWorkerState
import com.coppermux.cutools.tools.application.models.StorageCapabilityStatus
import com.coppermux.cutools.tools.application.models.StorageUsageModel

/**
 * Immutable snapshot of the unified DevTools Application panel state, aggregated
 * across every Application storage engine for the currently attached target.
 *
 * [storage] / [domStorage] / [cookies] / [serviceWorkers] carry the genuine
 * engine state (or `null` when the engine has not reported one yet);
 * [storageCapability] / [cookieCapability] / [indexedDbCapability] /
 * [cacheStorageCapability] mirror the domain capability each engine resolved
 * without fabrication (No-Fake-Capability rule). [sessionId] / [targetId] /
 * [attached] describe the active target.
 */
data class ApplicationViewModelSnapshot(
    val sessionId: String? = null,
    val targetId: String? = null,
    val attached: Boolean = false,
    val storage: StorageUsageModel? = null,
    val storageCapability: StorageCapabilityStatus? = null,
    val domStorage: DOMStorageState? = null,
    val cookies: List<CookieModel>? = null,
    val cookieCapability: CookieCapabilityStatus? = null,
    val indexedDbCapability: IndexedDbCapabilityStatus? = null,
    val cacheStorageCapability: CacheStorageCapabilityStatus? = null,
    val serviceWorkers: ServiceWorkerState? = null,
) {
    companion object {
        fun empty(): ApplicationViewModelSnapshot = ApplicationViewModelSnapshot()
    }
}

/**
 * Unified DevTools Application panel ViewModel.
 *
 * Orchestrates the six Application storage subsystems — Storage/Quota,
 * DOMStorage, Cookies, IndexedDB, CacheStorage, and Service Workers — into a
 * single [state] snapshot. Target attach/detach actions are fanned out to every
 * engine in unison so their session-bound state clears and refreshes together.
 *
 * Threading: the aggregated [state] is `@Volatile` and rebuilt non-blocking by
 * reading each engine's own atomic/volatile state, so concurrent event streams
 * across engines never produce a torn snapshot. No Chrome communication is
 * performed here — all commands are delegated to the shared engines.
 */
class ApplicationToolViewModel(
    private val storage: StorageEngine,
    private val domStorage: DOMStorageEngine,
    private val cookies: CookieEngine,
    private val indexedDb: IndexedDbEngine,
    private val cacheStorage: CacheStorageEngine,
    private val serviceWorkers: ServiceWorkerEngine,
) {

    /** The most recent aggregated snapshot of all Application subsystems. */
    @Volatile
    var state: ApplicationViewModelSnapshot = ApplicationViewModelSnapshot.empty()
        private set

    /** The active attached target session, or null when detached. */
    @Volatile
    var attachedSession: SessionInfo? = null
        private set

    val isAttached: Boolean get() = attachedSession != null

    // ================================================================
    // Target lifecycle
    // ================================================================

    /**
     * Attaches every Application engine to a newly-attached target session,
     * clearing each engine's prior target state in unison.
     */
    fun attachTarget(session: SessionInfo) {
        storage.bindSession(session)
        domStorage.bindSession(session)
        cookies.bindSession(session)
        indexedDb.bindSession(session)
        cacheStorage.bindSession(session)
        serviceWorkers.bindSession(session)
        attachedSession = session
        refresh()
    }

    /** Detaches every Application engine, clearing all target-scoped state. */
    fun detachTarget() {
        storage.detach()
        domStorage.detach()
        cookies.detach()
        indexedDb.detach()
        cacheStorage.detach()
        serviceWorkers.detach()
        attachedSession = null
        refresh()
    }

    /** Handles connection loss across all Application engines. */
    fun onConnectionLost() {
        storage.onConnectionLost()
        domStorage.onConnectionLost()
        cookies.onConnectionLost()
        indexedDb.onConnectionLost()
        cacheStorage.onConnectionLost()
        serviceWorkers.onConnectionLost()
        attachedSession = null
        refresh()
    }

    /** Releases event subscriptions across all event-driven engines. */
    fun shutdown() {
        domStorage.shutdown()
        serviceWorkers.shutdown()
    }

    // ================================================================
    // Aggregation
    // ================================================================

    /**
* Rebuilds the aggregated [state] snapshot from each engine's current
     * state. Each read is non-blocking (each engine field is atomic/volatile).
     */
    fun refresh() {
        val active = attachedSession
        state = ApplicationViewModelSnapshot(
            sessionId = active?.sessionId,
            targetId = active?.targetId,
            attached = active != null,
            storage = storage.latestUsage,
            storageCapability = storage.capability,
            domStorage = domStorage.state,
            cookies = cookies.latestCookies,
            cookieCapability = cookies.capability,
            indexedDbCapability = indexedDb.capability,
            cacheStorageCapability = cacheStorage.capability,
            serviceWorkers = serviceWorkers.state,
        )
    }
}