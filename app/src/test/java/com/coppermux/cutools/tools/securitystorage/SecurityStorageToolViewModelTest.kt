package com.coppermux.cutools.tools.securitystorage

import com.coppermux.cutools.core.security.SecurityEngine
import com.coppermux.cutools.core.security.models.SecurityActionResult
import com.coppermux.cutools.core.security.models.SecurityStateModel
import com.coppermux.cutools.core.storage.CacheStorageEngine
import com.coppermux.cutools.core.storage.IndexedDbEngine
import com.coppermux.cutools.core.storage.StorageEngine
import com.coppermux.cutools.core.storage.models.StorageActionResult
import com.coppermux.cutools.core.storage.models.StorageIntrospectionActionResult
import com.coppermux.cutools.core.storage.models.StorageTypeModel
import com.coppermux.cutools.core.storage.models.StorageUsageAndQuotaModel
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.tools.securitystorage.models.SecurityStorageTab
import com.coppermux.cutools.tools.securitystorage.viewmodel.SecurityStorageToolViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end test suite for the Sub-Phase 8D unified DevTools Security &
 * Storage panel ViewModel.
 *
 * Every security / storage engine (Security, Storage, IndexedDB, CacheStorage)
 * is constructed against the same fake transport/router and driven through raw
 * CDP JSON, then the ViewModel's attach / detach / connection-loss
 * coordination, state aggregation, tab transitions, storage clearance
 * dispatch, and error propagation into the snapshot are verified in the same
 * pipeline the app uses against Chrome.
 */
class SecurityStorageToolViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var security: SecurityEngine
    private lateinit var storage: StorageEngine
    private lateinit var indexedDb: IndexedDbEngine
    private lateinit var cacheStorage: CacheStorageEngine
    private lateinit var viewModel: SecurityStorageToolViewModel

    private val session = SessionInfo("session-1", "target-1", "ws://test")
    private val otherSession = SessionInfo("session-2", "target-2", "ws://test")
    private val origin = "https://example.com/"

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        security = SecurityEngine(dispatcher, router)
        storage = StorageEngine(dispatcher, router)
        indexedDb = IndexedDbEngine(dispatcher)
        cacheStorage = CacheStorageEngine(dispatcher)
        viewModel = SecurityStorageToolViewModel(security, storage, indexedDb, cacheStorage)
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitFor(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun deliverEvent(method: String, paramsJson: String) {
        transport.deliverMessage("""{"method":"$method","sessionId":"session-1","params":$paramsJson}""")
    }

    // ============ populate genuine engine state ============

    private fun populateSecurity() {
        deliverEvent(
            "Security.securityStateChanged",
            """{"securityState":"secure","schemeIsCryptographic":true,
                "explanations":[{"securityState":"secure","title":"Secure","summary":"OK"}],
                "certificateSecurityState":{"subjectName":"example.com","issuer":"Let's Encrypt","protocol":"TLS 1.3","cipher":"AES_128_GCM"}}"""
        )
        assertTrue(waitFor { security.securityState == SecurityStateModel.SECURE })
    }

    private fun populateQuota() {
        viewModel.refreshQuota(origin) {}
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"usage":2048,"quota":4096,"usageBreakdown":[{"storageType":"local_storage","usage":512}]}}"""
        )
        assertTrue(waitFor { storage.latestUsage != null })
    }

    private fun populateDatabaseNames() {
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        viewModel.requestDatabaseNames(origin) { results.add(it) }
        val id = parseIdFor("IndexedDB.requestDatabaseNames")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"databaseNames":["db1"]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
    }

    private fun populateCacheBuckets() {
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<com.coppermux.cutools.core.storage.models.CacheStorageBucketModel>>>()
        viewModel.requestCacheNames(origin) { results.add(it) }
        val id = parseIdFor("CacheStorage.requestCacheNames")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"caches":[{"cacheId":"c-1","securityOrigin":"https://example.com/","cacheName":"bucket"}]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
    }

    // ============ target attach / detach coordination ============

    @Test
    fun attachTarget_bindsAllFourEnginesInUnison() {
        viewModel.attachTarget(session)
        assertEquals(session, security.session)
        assertEquals(session, storage.session)
        assertEquals(session, indexedDb.session)
        assertEquals(session, cacheStorage.session)
        assertEquals(session, viewModel.attachedSession)
        assertTrue(viewModel.isAttached)
        assertTrue(viewModel.state.attached)
        assertEquals("session-1", viewModel.state.sessionId)
        assertEquals("target-1", viewModel.state.targetId)
    }

    @Test
    fun attachTarget_enablesEventDrivenDomains() {
        viewModel.attachTarget(session)
        assertTrue(transport.sentMessages.any { it.contains("Security.enable") })
    }

    @Test
    fun detachTarget_clearsAllFourEnginesInUnison() {
        viewModel.attachTarget(session)
        populateSecurity()
        viewModel.detachTarget()
        assertNull(security.session)
        assertNull(storage.session)
        assertNull(indexedDb.session)
        assertNull(cacheStorage.session)
        assertNull(viewModel.attachedSession)
        assertFalse(viewModel.isAttached)
        assertFalse(viewModel.state.attached)
        assertNull(viewModel.state.securityState)
    }

    @Test
    fun onConnectionLost_detachesAllFourEngines() {
        viewModel.attachTarget(session)
        viewModel.onConnectionLost()
        assertNull(security.session)
        assertNull(storage.session)
        assertNull(indexedDb.session)
        assertNull(cacheStorage.session)
        assertNull(viewModel.attachedSession)
    }

    @Test
    fun attachTarget_transitionsBetweenTargetsAndResetsState() {
        viewModel.attachTarget(session)
        populateSecurity()
        viewModel.attachTarget(otherSession)
        assertEquals(otherSession, security.session)
        assertEquals(otherSession, storage.session)
        assertEquals(otherSession, indexedDb.session)
        assertEquals(otherSession, cacheStorage.session)
        assertEquals(otherSession, viewModel.attachedSession)
        assertNull(viewModel.state.securityState)
        assertEquals("session-2", viewModel.state.sessionId)
    }

    // ============ state aggregation & tab transitions ============

    @Test
    fun refresh_aggregatesStatesFromAllFourEngines() {
        viewModel.attachTarget(session)
        populateSecurity()
        populateQuota()
        populateDatabaseNames()
        populateCacheBuckets()
        viewModel.refresh()

        val snapshot = viewModel.state
        assertTrue(snapshot.attached)
        assertEquals(SecurityStateModel.SECURE, snapshot.securityState)
        assertTrue(snapshot.schemeIsCryptographic)
        assertEquals("example.com", snapshot.certificateDetails!!.subjectName)
        assertEquals(2048L, snapshot.quotaUsage!!.usage)
        assertEquals(listOf("db1"), snapshot.databaseNames)
        assertEquals("c-1", snapshot.cacheBuckets[0].cacheId)
    }

    @Test
    fun refresh_reportsEmptyStatesBeforePopulated() {
        viewModel.attachTarget(session)
        viewModel.refresh()
        val snapshot = viewModel.state
        assertTrue(snapshot.attached)
        assertNull(snapshot.securityState)
        assertNull(snapshot.quotaUsage)
        assertTrue(snapshot.databaseNames.isEmpty())
        assertTrue(snapshot.cacheBuckets.isEmpty())
    }

    @Test
    fun selectTab_updatesSnapshotTab() {
        viewModel.attachTarget(session)
        assertEquals(SecurityStorageTab.SECURITY, viewModel.state.tab)
        viewModel.selectTab(SecurityStorageTab.INDEXED_DB)
        assertEquals(SecurityStorageTab.INDEXED_DB, viewModel.state.tab)
        assertEquals(SecurityStorageTab.INDEXED_DB, viewModel.tab)
        viewModel.selectTab(SecurityStorageTab.CACHE_STORAGE)
        assertEquals(SecurityStorageTab.CACHE_STORAGE, viewModel.state.tab)
    }

    // ============ query caching & clearance dispatch ============

    @Test
    fun requestDatabaseNames_cachesResultIntoSnapshot() {
        viewModel.attachTarget(session)
        populateDatabaseNames()
        viewModel.refresh()
        assertEquals(listOf("db1"), viewModel.state.databaseNames)
        assertNull(viewModel.state.indexedDbError)
    }

    @Test
    fun requestCacheNames_cachesBucketsIntoSnapshot() {
        viewModel.attachTarget(session)
        populateCacheBuckets()
        viewModel.refresh()
        assertEquals("bucket", viewModel.state.cacheBuckets[0].cacheName)
    }

    @Test
    fun clearDataForOrigin_dispatchesStorageClearanceCommand() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        viewModel.clearDataForOrigin(origin, listOf(StorageTypeModel.ALL)) { results.add(it) }
        assertTrue(transport.sentMessages.any { it.contains("Storage.clearDataForOrigin") })
        assertTrue(transport.sentMessages.any { it.contains("\"storageTypes\":\"all\"") })
        val id = parseIdFor("Storage.clearDataForOrigin")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageActionResult.Success)
    }

    // ============ error propagation ============

    @Test
    fun refreshQuota_protocolFailure_propagatesStorageError() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageAndQuotaModel>>()
        viewModel.refreshQuota(origin) { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"quota broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageActionResult.Failure)
        assertTrue(viewModel.state.storageError.orEmpty().contains("quota broke"))
    }

    @Test
    fun enableSecurity_protocolFailure_propagatesSecurityError() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<SecurityActionResult<Unit>>()
        viewModel.enableSecurity { results.add(it) }
        val id = parseIdFor("Security.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"security broke"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is SecurityActionResult.Failure)
        assertTrue(viewModel.state.securityError.orEmpty().contains("security broke"))
    }

    @Test
    fun requestDatabaseNames_methodUnavailable_propagatesUnsupportedBanner() {
        viewModel.attachTarget(session)
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        viewModel.requestDatabaseNames(origin) { results.add(it) }
        val id = parseIdFor("IndexedDB.requestDatabaseNames")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(viewModel.state.indexedDbError.orEmpty().contains("Unsupported:"))
    }

    // ============ shutdown ============

    @Test
    fun shutdown_releasesEventDrivenSubscriptions() {
        viewModel.attachTarget(session)
        assertEquals(1, router.subscriptionCount("Security.securityStateChanged"))
        assertEquals(1, router.subscriptionCount("Security.certificateError"))
        assertEquals(1, router.subscriptionCount("Storage.cacheStorageContentUpdated"))
        assertEquals(1, router.subscriptionCount("Storage.cacheStorageListUpdated"))

        viewModel.shutdown()
        assertEquals(0, router.subscriptionCount("Security.securityStateChanged"))
        assertEquals(0, router.subscriptionCount("Security.certificateError"))
        assertEquals(0, router.subscriptionCount("Storage.cacheStorageContentUpdated"))
        assertEquals(0, router.subscriptionCount("Storage.cacheStorageListUpdated"))
    }
}