package com.coppermux.cutools.tools.application

import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.tools.application.engine.DOMStorageEngine
import com.coppermux.cutools.tools.application.models.DOMStorageActionStatus
import com.coppermux.cutools.tools.application.models.DOMStorageActionResult
import com.coppermux.cutools.tools.application.models.DOMStorageCapabilityStatus
import com.coppermux.cutools.tools.application.models.DOMStorageItemModel
import com.coppermux.cutools.tools.application.models.DOMStorageType
import com.coppermux.cutools.tools.application.models.StorageIdModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Sub-Phase 6B DOMStorage (LocalStorage &
 * SessionStorage).
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → router → dispatcher → engine, proving
 * `DOMStorage.enable/disable/getDOMStorageItems/setDOMStorageItem/
 * removeDOMStorageItem/clear` serialization, genuine event routing, and atomic
 * store updates in the same pipeline the app uses against Chrome. No-Fake-
 * Capability is enforced: protocol failures map to explicit UNSUPPORTED /
 * UNAVAILABLE / FAILED outcomes, never fabricated storage values.
 */
class DOMStorageEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: DOMStorageEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")
    private val local = StorageIdModel("https://example.com/", true)
    private val sessionStorage = StorageIdModel("https://example.com/", false)

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = DOMStorageEngine(dispatcher, router)
    }

    private fun bind() {
        engine.bindSession(session)
        val id = parseIdFor("DOMStorage.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.enabled })
    }

    /** Binds and establishes `local` as the actively inspected storage area. */
    private fun establishInspecting() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<List<DOMStorageItemModel>>>()
        engine.getItems(local) { results.add(it) }
        val id = parseIdFor("DOMStorage.getDOMStorageItems")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"entries":[{"key":"a","value":"1"},{"key":"b","value":"2"}]}}"""
        )
        assertTrue(waitFor { results.size == 1 && engine.state.storageId != null })
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

    private fun storageIdValue(isLocalStorage: Boolean): String =
        """{"securityOrigin":"https://example.com/","isLocalStorage":$isLocalStorage}"""

    // ============ payload generation ============

    @Test
    fun bind_sendsDomStorageEnableOnSession() {
        engine.bindSession(session)
        val raw = transport.sentMessages.last { it.contains("DOMStorage.enable") }
        assertTrue(raw.contains("\"method\":\"DOMStorage.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun disable_serializesDisableAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.disable { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("DOMStorage.disable") }
        assertTrue(raw.contains("\"method\":\"DOMStorage.disable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun getItems_serializesStorageIdAndSessionId() {
        bind()
        engine.getItems(local) {}
        val raw = transport.sentMessages.last { it.contains("DOMStorage.getDOMStorageItems") }
        assertTrue(raw.contains("\"method\":\"DOMStorage.getDOMStorageItems\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com/\""))
        assertTrue(raw.contains("\"isLocalStorage\":true"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun setItem_serializesStorageIdKeyValueAndSessionId() {
        bind()
        engine.setItem(local, "theme", "dark") {}
        val raw = transport.sentMessages.last { it.contains("DOMStorage.setDOMStorageItem") }
        assertTrue(raw.contains("\"method\":\"DOMStorage.setDOMStorageItem\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com/\""))
        assertTrue(raw.contains("\"isLocalStorage\":true"))
        assertTrue(raw.contains("\"key\":\"theme\""))
        assertTrue(raw.contains("\"value\":\"dark\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun setItem_includesStorageKeyWhenPresent() {
        bind()
        val sid = StorageIdModel("https://example.com/", true, storageKey = "abc")
        engine.setItem(sid, "k", "v") {}
        val raw = transport.sentMessages.last { it.contains("DOMStorage.setDOMStorageItem") }
        assertTrue(raw.contains("\"storageKey\":\"abc\""))
    }

    @Test
    fun removeItem_serializesStorageIdKeyAndSessionId() {
        bind()
        engine.removeItem(local, "theme") {}
        val raw = transport.sentMessages.last { it.contains("DOMStorage.removeDOMStorageItem") }
        assertTrue(raw.contains("\"method\":\"DOMStorage.removeDOMStorageItem\""))
        assertTrue(raw.contains("\"key\":\"theme\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun clear_serializesStorageIdAndSessionId() {
        bind()
        engine.clear(sessionStorage) {}
        val raw = transport.sentMessages.last { it.contains("DOMStorage.clear") }
        assertTrue(raw.contains("\"method\":\"DOMStorage.clear\""))
        assertTrue(raw.contains("\"isLocalStorage\":false"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ parsing ============

    @Test
    fun getItems_success_parsesEntriesAndPopulatesState() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<List<DOMStorageItemModel>>>()
        engine.getItems(local) { results.add(it) }
        val id = parseIdFor("DOMStorage.getDOMStorageItems")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"entries":[{"key":"theme","value":"dark"},{"key":"lang","value":"en"}]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.SUCCESS, results[0].status)
        val items = results[0].value!!
        assertEquals(2, items.size)
        assertEquals("theme", items[0].key)
        assertEquals("dark", items[0].value)
        assertEquals(local, engine.state.storageId)
        assertEquals("https://example.com/", engine.state.origin)
        assertEquals(DOMStorageType.LOCAL_STORAGE, engine.state.storageType)
        assertEquals(DOMStorageCapabilityStatus.SUPPORTED, engine.capability)
        assertEquals("dark", engine.state.items["theme"])
        assertEquals(2, engine.state.itemCount)
    }

    @Test
    fun getItems_success_missingEntries_returnsEmptyList() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<List<DOMStorageItemModel>>>()
        engine.getItems(local) { results.add(it) }
        val id = parseIdFor("DOMStorage.getDOMStorageItems")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.SUCCESS, results[0].status)
        assertTrue(results[0].value!!.isEmpty())
        assertEquals(0, engine.state.itemCount)
    }

    @Test
    fun getItems_missingResultObject_failsExplicitly() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<List<DOMStorageItemModel>>>()
        engine.getItems(local) { results.add(it) }
        val id = parseIdFor("DOMStorage.getDOMStorageItems")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":123}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.FAILED, results[0].status)
        assertNull(results[0].value)
        assertEquals(DOMStorageCapabilityStatus.FAILED, engine.capability)
        assertEquals(0, engine.state.itemCount)
    }

    // ============ error mapping ============

    @Test
    fun getItems_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<DOMStorageActionResult<List<DOMStorageItemModel>>>()
        engine.getItems(local) { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(DOMStorageActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(DOMStorageCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun setItem_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.setItem(local, "k", "v") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(DOMStorageActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun removeItem_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.removeItem(local, "k") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(DOMStorageActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun clear_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.clear(local) { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(DOMStorageActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun enable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.enable { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(DOMStorageActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun disable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(DOMStorageActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getItems_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<List<DOMStorageItemModel>>>()
        engine.getItems(local) { results.add(it) }
        val id = parseIdFor("DOMStorage.getDOMStorageItems")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"boom"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.FAILED, results[0].status)
        assertTrue(results[0].message.orEmpty().contains("boom"))
        assertTrue(results[0].throwable != null)
        assertEquals(DOMStorageCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun getItems_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<List<DOMStorageItemModel>>>()
        engine.getItems(local) { results.add(it) }
        val id = parseIdFor("DOMStorage.getDOMStorageItems")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(DOMStorageCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    @Test
    fun setItem_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.setItem(local, "k", "v") { results.add(it) }
        val id = parseIdFor("DOMStorage.setDOMStorageItem")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"set broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.FAILED, results[0].status)
        assertEquals(DOMStorageCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun setItem_setItemError_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.setItem(local, "k", "v") { results.add(it) }
        val id = parseIdFor("DOMStorage.setDOMStorageItem")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"Failed to set DOMStorageItem"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.FAILED, results[0].status)
    }

    @Test
    fun removeItem_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.removeItem(local, "k") { results.add(it) }
        val id = parseIdFor("DOMStorage.removeDOMStorageItem")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(DOMStorageCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    @Test
    fun clear_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.clear(local) { results.add(it) }
        val id = parseIdFor("DOMStorage.clear")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"clear broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.FAILED, results[0].status)
        assertEquals(DOMStorageCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun enable_methodUnavailable_mapsToUnsupported() {
        engine.bindSession(session)
        val id = parseIdFor("DOMStorage.enable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { engine.capability == DOMStorageCapabilityStatus.UNSUPPORTED })
        assertEquals(DOMStorageCapabilityStatus.UNSUPPORTED, engine.state.capability)
        assertTrue(!engine.state.enabled)
    }

    @Test
    fun enable_protocolFailure_mapsToFailed() {
        engine.bindSession(session)
        val id = parseIdFor("DOMStorage.enable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"nope"}}"""
        )
        assertTrue(waitFor { engine.capability == DOMStorageCapabilityStatus.FAILED })
        assertEquals(DOMStorageCapabilityStatus.FAILED, engine.state.capability)
    }

    // ============ mutation success ============

    @Test
    fun setItem_success_returnsSuccessAndUpdatesStore() {
        establishInspecting()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.setItem(local, "c", "3") { results.add(it) }
        val id = parseIdFor("DOMStorage.setDOMStorageItem")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.SUCCESS, results[0].status)
        assertTrue(waitFor { engine.state.items["c"] == "3" })
        assertEquals(DOMStorageCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun removeItem_success_removesFromStore() {
        establishInspecting()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.removeItem(local, "a") { results.add(it) }
        val id = parseIdFor("DOMStorage.removeDOMStorageItem")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.SUCCESS, results[0].status)
        assertTrue(waitFor { engine.state.items["a"] == null })
        assertEquals(1, engine.state.itemCount)
    }

    @Test
    fun clear_success_emptiesStore() {
        establishInspecting()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.clear(local) { results.add(it) }
        val id = parseIdFor("DOMStorage.clear")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.SUCCESS, results[0].status)
        assertTrue(waitFor { engine.state.items.isEmpty() })
        assertEquals(0, engine.state.itemCount)
    }

    @Test
    fun disable_success_disablesDomain() {
        bind()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<Unit>>()
        engine.disable { results.add(it) }
        val id = parseIdFor("DOMStorage.disable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(DOMStorageActionStatus.SUCCESS, results[0].status)
        assertTrue(waitFor { !engine.state.enabled })
    }

    // ============ event listener routing & atomic updates ============

    @Test
    fun event_itemAdded_updatesStoreAtomically() {
        establishInspecting()
        deliverEvent(
            "DOMStorage.domStorageItemAdded",
            """{"storageId":${storageIdValue(true)},"key":"c","newValue":"3"}"""
        )
        assertTrue(waitFor { engine.state.items["c"] == "3" })
        assertEquals(3, engine.state.itemCount)
    }

    @Test
    fun event_itemRemoved_removesFromStore() {
        establishInspecting()
        deliverEvent(
            "DOMStorage.domStorageItemRemoved",
            """{"storageId":${storageIdValue(true)},"key":"a"}"""
        )
        assertTrue(waitFor { engine.state.items["a"] == null })
        assertEquals(1, engine.state.itemCount)
    }

    @Test
    fun event_itemUpdated_updatesValue() {
        establishInspecting()
        deliverEvent(
            "DOMStorage.domStorageItemUpdated",
            """{"storageId":${storageIdValue(true)},"key":"a","oldValue":"1","newValue":"10"}"""
        )
        assertTrue(waitFor { engine.state.items["a"] == "10" })
        assertEquals(2, engine.state.itemCount)
    }

    @Test
    fun event_itemsCleared_emptiesStore() {
        establishInspecting()
        deliverEvent("DOMStorage.domStorageItemsCleared", """{"storageId":${storageIdValue(true)}}""")
        assertTrue(waitFor { engine.state.items.isEmpty() })
        assertEquals(0, engine.state.itemCount)
    }

    @Test
    fun event_forOtherSession_isIgnored() {
        establishInspecting()
        transport.deliverMessage(
            """{"method":"DOMStorage.domStorageItemAdded","sessionId":"other-session","params":{"storageId":${storageIdValue(true)},"key":"c","newValue":"3"}}"""
        )
        Thread.sleep(200)
        assertEquals(2, engine.state.itemCount)
    }

    @Test
    fun event_forOtherStorageId_isIgnored() {
        establishInspecting()
        deliverEvent(
            "DOMStorage.domStorageItemAdded",
            """{"storageId":${storageIdValue(false)},"key":"c","newValue":"3"}"""
        )
        Thread.sleep(200)
        assertEquals(2, engine.state.itemCount)
    }

    @Test
    fun event_malformedPayload_isTolerated() {
        establishInspecting()
        deliverEvent("DOMStorage.domStorageItemAdded", """{"storageId":${storageIdValue(true)}}""")
        deliverEvent("DOMStorage.domStorageItemRemoved", "{}")
        Thread.sleep(200)
        assertEquals(2, engine.state.itemCount)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsStateAndUnsubscribes() {
        establishInspecting()
        for (method in listOf(
            "DOMStorage.domStorageItemAdded",
            "DOMStorage.domStorageItemRemoved",
            "DOMStorage.domStorageItemUpdated",
            "DOMStorage.domStorageItemsCleared",
        )) {
            assertEquals(1, router.subscriptionCount(method))
        }

        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
        assertEquals(0, engine.state.itemCount)
        assertTrue(engine.state.items.isEmpty())
        for (method in listOf(
            "DOMStorage.domStorageItemAdded",
            "DOMStorage.domStorageItemRemoved",
            "DOMStorage.domStorageItemUpdated",
            "DOMStorage.domStorageItemsCleared",
        )) {
            assertEquals("no event-listener leak for $method", 0, router.subscriptionCount(method))
        }
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        establishInspecting()
        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
        assertNull(engine.state.storageId)
        assertEquals(0, engine.state.itemCount)
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        establishInspecting()
        engine.onConnectionLost()
        assertNull(engine.session)
        assertEquals(0, engine.state.itemCount)
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        establishInspecting()
        val results = CopyOnWriteArrayList<DOMStorageActionResult<List<DOMStorageItemModel>>>()
        engine.getItems(local) { results.add(it) }
        val id = parseIdFor("DOMStorage.getDOMStorageItems")

        engine.detach()
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"entries":[{"key":"z","value":"9"}]}}"""
        )
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertEquals(0, engine.state.itemCount)
        assertNull(engine.session)
    }

    @Test
    fun lateEventAfterDetach_isIgnored() {
        establishInspecting()
        engine.detach()
        transport.deliverMessage(
            """{"method":"DOMStorage.domStorageItemAdded","sessionId":"session-1","params":{"storageId":${storageIdValue(true)},"key":"c","newValue":"3"}}"""
        )
        Thread.sleep(200)
        assertEquals(0, engine.state.itemCount)
    }

    @Test
    fun rebind_doesNotAccumulateSubscriptions() {
        engine.bindSession(session)
        val id1 = parseIdFor("DOMStorage.enable")
        transport.deliverMessage("""{"id":$id1,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.state.enabled })

        val reconnected = SessionInfo("session-3", "target-3", "ws://test")
        engine.bindSession(reconnected)
        val id2 = parseIdFor("DOMStorage.enable")
        transport.deliverMessage("""{"id":$id2,"sessionId":"session-3","result":{}}""")
        assertTrue(waitFor { engine.state.enabled && engine.session == reconnected })

        assertEquals(
            "rebinding must not leak extra listeners",
            1,
            router.subscriptionCount("DOMStorage.domStorageItemAdded"),
        )
    }

    // ============ model-level mapping ============

    @Test
    fun domStorageType_fromWire_mapsLocalAndSession() {
        assertEquals(DOMStorageType.LOCAL_STORAGE, DOMStorageType.fromWire(true))
        assertEquals(DOMStorageType.SESSION_STORAGE, DOMStorageType.fromWire(false))
        assertEquals(DOMStorageType.SESSION_STORAGE, DOMStorageType.fromWire(null))
    }

    @Test
    fun storageIdModel_storageTypeReflectsFlag() {
        assertEquals(DOMStorageType.LOCAL_STORAGE, local.storageType)
        assertEquals(DOMStorageType.SESSION_STORAGE, sessionStorage.storageType)
    }

    @Test
    fun storageIdModel_matches_comparesOriginAndFlag() {
        val sameOrigin = StorageIdModel("https://example.com/", true)
        val differentOrigin = StorageIdModel("https://other.com/", true)
        val session = StorageIdModel("https://example.com/", false)
        assertTrue(local.matches(sameOrigin))
        assertTrue(!local.matches(differentOrigin))
        assertTrue(!local.matches(session))
        assertTrue(!local.matches(null))
    }

    @Test
    fun domStorageActionResult_factories_produceExpectedStatuses() {
        val success = DOMStorageActionResult.success(Unit)
        assertEquals(DOMStorageActionStatus.SUCCESS, success.status)
        assertTrue(success.isSuccess)

        val unsupported = DOMStorageActionResult.unsupported<String>("nope", RuntimeException("cause"))
        assertEquals(DOMStorageActionStatus.UNSUPPORTED, unsupported.status)
        assertEquals("nope", unsupported.message)
        assertTrue(unsupported.throwable != null)

        val unavailable = DOMStorageActionResult.unavailable<Int>("not bound")
        assertEquals(DOMStorageActionStatus.UNAVAILABLE, unavailable.status)

        val failed = DOMStorageActionResult.failed<Long>("boom")
        assertEquals(DOMStorageActionStatus.FAILED, failed.status)
        assertTrue(!failed.isSuccess)
    }
}