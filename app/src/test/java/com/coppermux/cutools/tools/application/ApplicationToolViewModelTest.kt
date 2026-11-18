package com.coppermux.cutools.tools.application

import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.tools.application.engine.CacheStorageEngine
import com.coppermux.cutools.tools.application.engine.CookieEngine
import com.coppermux.cutools.tools.application.engine.DOMStorageEngine
import com.coppermux.cutools.tools.application.engine.IndexedDbEngine
import com.coppermux.cutools.tools.application.engine.ServiceWorkerEngine
import com.coppermux.cutools.tools.application.engine.StorageEngine
import com.coppermux.cutools.tools.application.models.StorageIdModel
import com.coppermux.cutools.tools.application.models.StorageCapabilityStatus
import com.coppermux.cutools.tools.application.viewmodel.ApplicationToolViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end test suite for the Sub-Phase 6E unified DevTools Application panel
 * ViewModel.
 *
 * Every Application engine (Storage, DOMStorage, Cookies, IndexedDB, CacheStorage,
 * Service Workers) is constructed against the same fake transport/router and
 * driven through raw CDP JSON, then the ViewModel's attach / detach / target
 * switch / shutdown coordination and aggregated state snapshot are verified in
 * the same pipeline the app uses against Chrome.
 */
class ApplicationToolViewModelTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var storage: StorageEngine
    private lateinit var domStorage: DOMStorageEngine
    private lateinit var cookies: CookieEngine
    private lateinit var indexedDb: IndexedDbEngine
    private lateinit var cacheStorage: CacheStorageEngine
    private lateinit var serviceWorkers: ServiceWorkerEngine
    private lateinit var viewModel: ApplicationToolViewModel

    private val session = SessionInfo("session-1", "target-1", "ws://test")
    private val otherSession = SessionInfo("session-2", "target-2", "ws://test")
    private val local = StorageIdModel("https://example.com/", true)

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        storage = StorageEngine(dispatcher)
        domStorage = DOMStorageEngine(dispatcher, router)
        cookies = CookieEngine(dispatcher)
        indexedDb = IndexedDbEngine(dispatcher)
        cacheStorage = CacheStorageEngine(dispatcher)
        serviceWorkers = ServiceWorkerEngine(dispatcher, router)
        viewModel = ApplicationToolViewModel(storage, domStorage, cookies, indexedDb, cacheStorage, serviceWorkers)
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

    /** Populates genuine state across several engines, then refreshes the snapshot. */
    private fun populateAndRefresh() {
        // Storage usage.
        storage.getUsageAndQuota("https://example.com/") {}
        val usageId = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage("""{"id":$usageId,"sessionId":"session-1","result":{"usage":1024,"quota":2048,"usageBreakdown":[{"storageType":"local_storage","usage":512}]}}""")
        assertTrue(waitFor { storage.latestUsage != null })

        // DOMStorage items.
        domStorage.getItems(local) {}
        val domId = parseIdFor("DOMStorage.getDOMStorageItems")
        transport.deliverMessage("""{"id":$domId,"sessionId":"session-1","result":{"entries":[{"key":"theme","value":"dark"}]}}""")
        assertTrue(waitFor { domStorage.state.storageId != null })

        // Cookies.
        cookies.getCookies(listOf("https://example.com/")) {}
        val cookieId = parseIdFor("Network.getCookies")
        transport.deliverMessage("""{"id":$cookieId,"sessionId":"session-1","result":{"cookies":[{"name":"sid","value":"abc"}]}}""")
        assertTrue(waitFor { cookies.latestCookies != null })

        // Service worker version event.
        transport.deliverMessage(
            """{"method":"ServiceWorker.workerVersionUpdated","sessionId":"session-1","params":{"versions":[{"versionId":"v-1","runningStatus":"running","status":"activated"}]}}"""
        )
        assertTrue(waitFor { serviceWorkers.state.versionCount == 1 })

        viewModel.refresh()
    }

    private fun parse(method: String): Long {
        val raw = transport.sentMessages.last { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    // ============ target attach coordination ============

    @Test
    fun attachTarget_bindsAllEnginesInUnison() {
        viewModel.attachTarget(session)
        assertEquals(session, storage.session)
        assertEquals(session, domStorage.session)
        assertEquals(session, cookies.session)
        assertEquals(session, indexedDb.session)
        assertEquals(session, cacheStorage.session)
        assertEquals(session, serviceWorkers.session)
        assertEquals(session, viewModel.attachedSession)
        assertTrue(viewModel.isAttached)
        assertTrue(viewModel.state.attached)
        assertEquals("session-1", viewModel.state.sessionId)
        assertEquals("target-1", viewModel.state.targetId)
    }

    @Test
    fun attachTarget_enablesEventDrivenDomains() {
        viewModel.attachTarget(session)
        assertTrue(transport.sentMessages.any { it.contains("ServiceWorker.enable") })
        assertTrue(transport.sentMessages.any { it.contains("DOMStorage.enable") })
    }

    @Test
    fun detachTarget_clearsAllEnginesInUnison() {
        viewModel.attachTarget(session)
        transport.deliverMessage(
            """{"method":"ServiceWorker.workerRegistrationUpdated","sessionId":"session-1","params":{"registrations":[{"registrationId":"reg-1","scopeURL":"https://a.example/"}]}}"""
        )
        assertTrue(waitFor { serviceWorkers.state.registrationCount == 1 })

        viewModel.detachTarget()
        assertNull(storage.session)
        assertNull(domStorage.session)
        assertNull(cookies.session)
        assertNull(indexedDb.session)
        assertNull(cacheStorage.session)
        assertNull(serviceWorkers.session)
        assertNull(viewModel.attachedSession)
        assertTrue(!viewModel.isAttached)
        assertTrue(!viewModel.state.attached)
        assertEquals(0, serviceWorkers.state.registrationCount)
        assertEquals(0, serviceWorkers.state.versionCount)
    }

    @Test
    fun onConnectionLost_detachesAllEngines() {
        viewModel.attachTarget(session)
        viewModel.onConnectionLost()
        assertNull(storage.session)
        assertNull(domStorage.session)
        assertNull(cookies.session)
        assertNull(indexedDb.session)
        assertNull(cacheStorage.session)
        assertNull(serviceWorkers.session)
        assertNull(viewModel.attachedSession)
    }

    @Test
    fun attachTarget_transitionsBetweenTargetsAndResetsState() {
viewModel.attachTarget(session)
        transport.deliverMessage(
            """{"method":"ServiceWorker.workerRegistrationUpdated","sessionId":"session-1","params":{"registrations":[{"registrationId":"reg-1","scopeURL":"https://a.example/"}]}}"""
        )
        assertTrue(waitFor { serviceWorkers.state.registrationCount == 1 })

        viewModel.attachTarget(otherSession)
        assertEquals(otherSession, storage.session)
        assertEquals(otherSession, domStorage.session)
        assertEquals(otherSession, cookies.session)
        assertEquals(otherSession, indexedDb.session)
        assertEquals(otherSession, cacheStorage.session)
        assertEquals(otherSession, serviceWorkers.session)
        assertEquals(otherSession, viewModel.attachedSession)
        assertEquals(0, serviceWorkers.state.registrationCount)
        assertEquals("session-2", viewModel.state.sessionId)
    }

    // ============ state aggregation ============

    @Test
    fun refresh_aggregatesStatesFromAllEngines() {
        viewModel.attachTarget(session)
        populateAndRefresh()
        val snapshot = viewModel.state
        assertTrue(snapshot.attached)
        assertEquals("session-1", snapshot.sessionId)
        assertEquals(1024L, snapshot.storage!!.usageBytes)
        assertEquals("https://example.com/", snapshot.domStorage!!.origin)
        assertEquals("dark", snapshot.domStorage!!.items["theme"])
        assertEquals(1, snapshot.cookies!!.size)
        assertEquals("sid", snapshot.cookies!![0].name)
        assertEquals(1, snapshot.serviceWorkers!!.versionCount)
    }

    @Test
    fun refresh_reportsNullStatesBeforePopulated() {
        viewModel.attachTarget(session)
        viewModel.refresh()
        val snapshot = viewModel.state
        assertTrue(snapshot.attached)
        assertNull(snapshot.storage)
        assertNull(snapshot.domStorage!!.storageId)
        assertNull(snapshot.cookies)
        assertNull(snapshot.serviceWorkers!!.capability)
        assertEquals(0, snapshot.serviceWorkers!!.versionCount)
    }

    // ============ shutdown / error propagation ============

    @Test
    fun shutdown_releasesEventDrivenSubscriptions() {
        viewModel.attachTarget(session)
        assertEquals(1, router.subscriptionCount("ServiceWorker.workerRegistrationUpdated"))
        assertEquals(1, router.subscriptionCount("DOMStorage.domStorageItemAdded"))

        viewModel.shutdown()
        assertEquals(0, router.subscriptionCount("ServiceWorker.workerRegistrationUpdated"))
        assertEquals(0, router.subscriptionCount("DOMStorage.domStorageItemAdded"))
    }

    @Test
    fun engineCapabilityFailures_propagateIntoSnapshot() {
        viewModel.attachTarget(session)
        storage.getUsageAndQuota("https://example.com/") {}
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { storage.capability != null })
        viewModel.refresh()
        assertEquals(
            StorageCapabilityStatus.UNSUPPORTED,
            viewModel.state.storageCapability,
        )
    }
}