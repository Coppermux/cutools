package com.coppermux.cutools.core.storage

import com.coppermux.cutools.core.storage.models.StorageActionResult
import com.coppermux.cutools.core.storage.models.StorageCapabilityStatus
import com.coppermux.cutools.core.storage.models.StorageError
import com.coppermux.cutools.core.storage.models.StorageTypeModel
import com.coppermux.cutools.core.storage.models.StorageUsageAndQuotaModel
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Sub-Phase 8B Storage Quota & Origin Data Engine.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → router → dispatcher → engine, proving
 * `Storage.getUsageAndQuota` / `Storage.clearDataForOrigin` /
 * `Storage.trackCacheStorageForOrigin` / `Storage.untrackCacheStorageForOrigin`
 * serialization, genuine `Storage.cacheStorageContentUpdated` /
 * `Storage.cacheStorageListUpdated` event routing, and strict usage/quota +
 * per-type breakdown parsing in the same pipeline the app uses against Chrome.
 * No-Fake-Capability is enforced: protocol failures, missing sessions,
 * unavailable methods, and connection loss map to explicit UNSUPPORTED /
 * UNAVAILABLE / FAILED / TARGET_DISCONNECTED outcomes, never fabricated storage
 * or cache state.
 */
class StorageEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: StorageEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = StorageEngine(dispatcher, router)
    }

    private fun bind() {
        engine.bindSession(session)
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

    private fun deliverSuccess(method: String, resultJson: String) {
        val id = parseIdFor(method)
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$resultJson}""")
    }

    private fun deliverError(method: String, code: Int, message: String) {
        val id = parseIdFor(method)
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":$code,"message":"$message"}}""")
    }

    // ============ payload generation ============

    @Test
    fun getUsageAndQuota_serializesOriginAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageAndQuotaModel>>()
        engine.getUsageAndQuota("https://example.com") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Storage.getUsageAndQuota") }
        assertTrue(raw.contains("\"method\":\"Storage.getUsageAndQuota\""))
        assertTrue(raw.contains("\"origin\":\"https://example.com\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun clearDataForOrigin_singleType_serializesStorageTypes() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin("https://example.com", listOf(StorageTypeModel.COOKIES)) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Storage.clearDataForOrigin") }
        assertTrue(raw.contains("\"method\":\"Storage.clearDataForOrigin\""))
        assertTrue(raw.contains("\"origin\":\"https://example.com\""))
        assertTrue(raw.contains("\"storageTypes\":\"cookies\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun clearDataForOrigin_multipleTypes_joinsCommaSeparated() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin(
            "https://example.com",
            listOf(StorageTypeModel.LOCAL_STORAGE, StorageTypeModel.CACHE_STORAGE, StorageTypeModel.INDEXEDDB),
        ) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Storage.clearDataForOrigin") }
        assertTrue(raw.contains("\"storageTypes\":\"local_storage,cache_storage,indexeddb\""))
    }

    @Test
    fun clearDataForOrigin_allType_serializesAll() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin("https://example.com", listOf(StorageTypeModel.ALL)) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Storage.clearDataForOrigin") }
        assertTrue(raw.contains("\"storageTypes\":\"all\""))
    }

    @Test
    fun trackCacheStorageForOrigin_serializesOriginScriptUrlAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.trackCacheStorageForOrigin("https://example.com", "https://example.com/app.js") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Storage.trackCacheStorageForOrigin") }
        assertTrue(raw.contains("\"method\":\"Storage.trackCacheStorageForOrigin\""))
        assertTrue(raw.contains("\"origin\":\"https://example.com\""))
        assertTrue(raw.contains("\"scriptURL\":\"https://example.com/app.js\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun untrackCacheStorageForOrigin_serializesOriginAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.untrackCacheStorageForOrigin("https://example.com") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Storage.untrackCacheStorageForOrigin") }
        assertTrue(raw.contains("\"method\":\"Storage.untrackCacheStorageForOrigin\""))
        assertTrue(raw.contains("\"origin\":\"https://example.com\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ quota & usage parsing ============

    @Test
    fun getUsageAndQuota_success_parsesUsageQuotaAndBreakdown() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageAndQuotaModel>>()
        engine.getUsageAndQuota("https://example.com") { results.add(it) }
        deliverSuccess(
            "Storage.getUsageAndQuota",
            """{"usage":1234,"quota":5000000,"usageBreakdown":[
                {"storageType":"cookies","usage":100},
                {"storageType":"local_storage","usage":234}
            ]}""",
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageActionResult.Success)
        val usage = (results[0] as StorageActionResult.Success).value
        assertEquals(1234L, usage.usage)
        assertEquals(5000000L, usage.quota)
        assertEquals(2, usage.usageBreakdown.size)
        assertEquals(StorageTypeModel.COOKIES, usage.usageBreakdown[0].storageType)
        assertEquals(100L, usage.usageBreakdown[0].usageBytes)
        assertEquals(StorageTypeModel.LOCAL_STORAGE, usage.usageBreakdown[1].storageType)
        assertEquals(234L, usage.usageBreakdown[1].usageBytes)
        assertEquals(StorageCapabilityStatus.SUPPORTED, engine.capability)
        assertTrue(engine.latestUsage === usage)
    }

    @Test
    fun getUsageAndQuota_breakdown_unknownTypeMapsToNull() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageAndQuotaModel>>()
        engine.getUsageAndQuota("https://example.com") { results.add(it) }
        deliverSuccess(
            "Storage.getUsageAndQuota",
            """{"usage":10,"quota":100,"usageBreakdown":[{"storageType":"mystery","usage":10}]}""",
        )
        assertTrue(waitFor { results.size == 1 })
        val usage = (results[0] as StorageActionResult.Success).value
        assertEquals(1, usage.usageBreakdown.size)
        assertNull(usage.usageBreakdown[0].storageType)
        assertEquals(10L, usage.usageBreakdown[0].usageBytes)
    }

    @Test
    fun getUsageAndQuota_missingBreakdown_isEmpty() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageAndQuotaModel>>()
        engine.getUsageAndQuota("https://example.com") { results.add(it) }
        deliverSuccess("Storage.getUsageAndQuota", """{"usage":10,"quota":100}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageActionResult.Success).value.usageBreakdown.isEmpty())
    }

    @Test
    fun getUsageAndQuota_missingMandatoryField_isFailure() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageAndQuotaModel>>()
        engine.getUsageAndQuota("https://example.com") { results.add(it) }
        deliverSuccess("Storage.getUsageAndQuota", """{"quota":100}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageActionResult.Failure)
        assertTrue((results[0] as StorageActionResult.Failure).error is StorageError.ExecutionFailed)
        assertEquals(StorageCapabilityStatus.FAILED, engine.capability)
        assertNull(engine.latestUsage)
    }

    // ============ data clearance success ============

    @Test
    fun clearDataForOrigin_success_returnsSuccessAndCapability() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin("https://example.com", listOf(StorageTypeModel.COOKIES)) { results.add(it) }
        deliverSuccess("Storage.clearDataForOrigin", "{}")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageActionResult.Success)
        assertEquals(StorageCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun trackCacheStorageForOrigin_success_addsTrackedOrigin() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.trackCacheStorageForOrigin("https://example.com", "https://example.com/app.js") { results.add(it) }
        deliverSuccess("Storage.trackCacheStorageForOrigin", "{}")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageActionResult.Success)
        assertTrue(engine.trackedOrigins.contains("https://example.com"))
        assertEquals(StorageCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun untrackCacheStorageForOrigin_success_removesTrackedOrigin() {
        bind()
        val track = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.trackCacheStorageForOrigin("https://example.com", "https://example.com/app.js") { track.add(it) }
        deliverSuccess("Storage.trackCacheStorageForOrigin", "{}")
        assertTrue(waitFor { engine.trackedOrigins.contains("https://example.com") })

        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.untrackCacheStorageForOrigin("https://example.com") { results.add(it) }
        deliverSuccess("Storage.untrackCacheStorageForOrigin", "{}")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageActionResult.Success)
        assertFalse(engine.trackedOrigins.contains("https://example.com"))
    }

    // ============ cache tracking events ============

    @Test
    fun cacheStorageContentUpdated_ingestsOriginAndKey() {
        bind()
        deliverEvent(
            "Storage.cacheStorageContentUpdated",
            """{"origin":"https://example.com","key":{"id":"bucket-1","storage":"https://example.com/"},"entry":{}}""",
        )
        assertTrue(waitFor { engine.cacheStorageContentUpdates.size == 1 })
        val update = engine.cacheStorageContentUpdates[0]
        assertEquals("https://example.com", update.origin)
        assertEquals("bucket-1", update.key.id)
        assertEquals("https://example.com/", update.key.storage)
        assertNull(update.content)
    }

    @Test
    fun cacheStorageContentUpdated_parsesEntryContent() {
        bind()
        deliverEvent(
            "Storage.cacheStorageContentUpdated",
            """{"origin":"https://example.com","key":{"id":"bucket-1","storage":"/"},"entry":{
                "request":{"url":"https://example.com/data.json","method":"GET"},
                "response":{"headers":[{"name":"Content-Type"},{"name":"Cache-Control"}]}
            }}""",
        )
        assertTrue(waitFor { engine.cacheStorageContentUpdates.size == 1 })
        val content = engine.cacheStorageContentUpdates[0].content!!
        assertEquals("https://example.com/data.json", content.requestUrl)
        assertEquals("GET", content.requestMethod)
        assertEquals(listOf("Content-Type", "Cache-Control"), content.responseHeaderNames)
    }

    @Test
    fun cacheStorageContentUpdated_malformed_isTolerated() {
        bind()
        deliverEvent("Storage.cacheStorageContentUpdated", "{}")
        deliverEvent("Storage.cacheStorageContentUpdated", "not-a-map")
        Thread.sleep(200)
        assertTrue(engine.cacheStorageContentUpdates.isEmpty())
    }

    @Test
    fun cacheStorageContentUpdated_forOtherSession_isIgnored() {
        bind()
        transport.deliverMessage(
            """{"method":"Storage.cacheStorageContentUpdated","sessionId":"other-session","params":{"origin":"https://example.com","key":{"id":"k"}}}""",
        )
        Thread.sleep(200)
        assertTrue(engine.cacheStorageContentUpdates.isEmpty())
    }

    @Test
    fun cacheStorageListUpdated_ingestsOrigin() {
        bind()
        deliverEvent("Storage.cacheStorageListUpdated", """{"origin":"https://example.com"}""")
        assertTrue(waitFor { engine.cacheStorageListUpdates.size == 1 })
        assertEquals("https://example.com", engine.cacheStorageListUpdates[0].origin)
    }

    @Test
    fun cacheStorageListUpdated_malformedOrOtherSession_isIgnored() {
        bind()
        deliverEvent("Storage.cacheStorageListUpdated", "{}")
        transport.deliverMessage(
            """{"method":"Storage.cacheStorageListUpdated","sessionId":"other-session","params":{"origin":"https://x.example"}}""",
        )
        Thread.sleep(200)
        assertTrue(engine.cacheStorageListUpdates.isEmpty())
    }

    // ============ error mapping ============

    @Test
    fun getUsageAndQuota_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageAndQuotaModel>>()
        engine.getUsageAndQuota("https://example.com") { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as StorageActionResult.Failure).error is StorageError.Unavailable)
        assertEquals(StorageCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun clearDataForOrigin_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin("https://example.com", listOf(StorageTypeModel.COOKIES)) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as StorageActionResult.Failure).error is StorageError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun trackCacheStorageForOrigin_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.trackCacheStorageForOrigin("https://example.com", "https://example.com/app.js") { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as StorageActionResult.Failure).error is StorageError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun untrackCacheStorageForOrigin_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.untrackCacheStorageForOrigin("https://example.com") { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as StorageActionResult.Failure).error is StorageError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun clearDataForOrigin_emptyTypes_isFailureWithoutSending() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin("https://example.com", emptyList()) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as StorageActionResult.Failure).error is StorageError.ExecutionFailed)
        assertEquals(StorageCapabilityStatus.FAILED, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getUsageAndQuota_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageAndQuotaModel>>()
        engine.getUsageAndQuota("https://example.com") { results.add(it) }
        deliverError("Storage.getUsageAndQuota", -32601, "Method not found")
        assertTrue(waitFor { engine.capability == StorageCapabilityStatus.UNSUPPORTED })
        assertTrue((results[0] as StorageActionResult.Failure).error is StorageError.Unsupported)
    }

    @Test
    fun getUsageAndQuota_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageAndQuotaModel>>()
        engine.getUsageAndQuota("https://example.com") { results.add(it) }
        deliverError("Storage.getUsageAndQuota", -32000, "usage broke")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as StorageActionResult.Failure
        assertTrue(failure.error is StorageError.ExecutionFailed)
        assertTrue((failure.error as StorageError.ExecutionFailed).cause.message.orEmpty().contains("usage broke"))
        assertEquals(StorageCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun clearDataForOrigin_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin("https://example.com", listOf(StorageTypeModel.COOKIES)) { results.add(it) }
        deliverError("Storage.clearDataForOrigin", -32000, "clear failed")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageActionResult.Failure).error is StorageError.ExecutionFailed)
        assertEquals(StorageCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun trackCacheStorageForOrigin_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.trackCacheStorageForOrigin("https://example.com", "https://example.com/app.js") { results.add(it) }
        deliverError("Storage.trackCacheStorageForOrigin", -32000, "track failed")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageActionResult.Failure).error is StorageError.ExecutionFailed)
        assertFalse(engine.trackedOrigins.contains("https://example.com"))
    }

    @Test
    fun getUsageAndQuota_afterConnectionLost_mapsToTargetDisconnected() {
        bind()
        transport.disconnect()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageAndQuotaModel>>()
        engine.getUsageAndQuota("https://example.com") { results.add(it) }
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageActionResult.Failure).error is StorageError.TargetDisconnected)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsStateAndUnsubscribes() {
        bind()
        deliverEvent(
            "Storage.cacheStorageContentUpdated",
            """{"origin":"https://example.com","key":{"id":"k","storage":"s"},"entry":{}}""",
        )
        deliverEvent("Storage.cacheStorageListUpdated", """{"origin":"https://example.com"}""")
        assertTrue(waitFor { engine.cacheStorageContentUpdates.size == 1 && engine.cacheStorageListUpdates.size == 1 })
        for (method in listOf("Storage.cacheStorageContentUpdated", "Storage.cacheStorageListUpdated")) {
            assertEquals("one listener per method", 1, router.subscriptionCount(method))
        }

        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
        assertNull(engine.latestUsage)
        assertTrue(engine.trackedOrigins.isEmpty())
        assertTrue(engine.cacheStorageContentUpdates.isEmpty())
        assertTrue(engine.cacheStorageListUpdates.isEmpty())
        for (method in listOf("Storage.cacheStorageContentUpdated", "Storage.cacheStorageListUpdated")) {
            assertEquals("no event-listener leak for $method", 0, router.subscriptionCount(method))
        }
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        deliverEvent(
            "Storage.cacheStorageContentUpdated",
            """{"origin":"https://example.com","key":{"id":"k"},"entry":{}}""",
        )
        assertTrue(waitFor { engine.cacheStorageContentUpdates.size == 1 })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
        assertNull(engine.latestUsage)
        assertTrue(engine.trackedOrigins.isEmpty())
        assertTrue(engine.cacheStorageContentUpdates.isEmpty())
        assertTrue(engine.cacheStorageListUpdates.isEmpty())
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        deliverEvent(
            "Storage.cacheStorageContentUpdated",
            """{"origin":"https://example.com","key":{"id":"k"},"entry":{}}""",
        )
        assertTrue(waitFor { engine.cacheStorageContentUpdates.size == 1 })
        engine.onConnectionLost()
        assertNull(engine.session)
        assertNull(engine.latestUsage)
        assertTrue(engine.cacheStorageContentUpdates.isEmpty())
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageAndQuotaModel>>()
        engine.getUsageAndQuota("https://example.com") { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")

        engine.detach()
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"usage":1,"quota":2}}""")
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
        assertNull(engine.latestUsage)
    }

    @Test
    fun lateEventAfterDetach_isIgnored() {
        bind()
        engine.detach()
        deliverEvent(
            "Storage.cacheStorageContentUpdated",
            """{"origin":"https://example.com","key":{"id":"k"},"entry":{}}""",
        )
        deliverEvent("Storage.cacheStorageListUpdated", """{"origin":"https://example.com"}""")
        Thread.sleep(200)
        assertTrue(engine.cacheStorageContentUpdates.isEmpty())
        assertTrue(engine.cacheStorageListUpdates.isEmpty())
    }

    @Test
    fun rebind_doesNotAccumulateSubscriptions() {
        engine.bindSession(session)
        val reconnected = SessionInfo("session-3", "target-3", "ws://test")
        engine.bindSession(reconnected)
        assertEquals(
            "rebinding must not leak extra listeners",
            1,
            router.subscriptionCount("Storage.cacheStorageContentUpdated"),
        )
        assertEquals(1, router.subscriptionCount("Storage.cacheStorageListUpdated"))
    }

    @Test
    fun shutdown_releasesSubscriptions() {
        engine.bindSession(session)
        assertEquals(1, router.subscriptionCount("Storage.cacheStorageContentUpdated"))
        engine.shutdown()
        assertEquals(0, router.subscriptionCount("Storage.cacheStorageContentUpdated"))
        assertEquals(0, router.subscriptionCount("Storage.cacheStorageListUpdated"))
    }

    // ============ model-level mapping ============

    @Test
    fun storageTypeModel_fromWire_mapsAllValues() {
        assertEquals(StorageTypeModel.APPCACHE, StorageTypeModel.fromWire("appcache"))
        assertEquals(StorageTypeModel.COOKIES, StorageTypeModel.fromWire("cookies"))
        assertEquals(StorageTypeModel.FILE_SYSTEMS, StorageTypeModel.fromWire("file_systems"))
        assertEquals(StorageTypeModel.INDEXEDDB, StorageTypeModel.fromWire("indexeddb"))
        assertEquals(StorageTypeModel.LOCAL_STORAGE, StorageTypeModel.fromWire("local_storage"))
        assertEquals(StorageTypeModel.SHADER_CACHE, StorageTypeModel.fromWire("shader_cache"))
        assertEquals(StorageTypeModel.WEBSQL, StorageTypeModel.fromWire("websql"))
        assertEquals(StorageTypeModel.SERVICE_WORKERS, StorageTypeModel.fromWire("service_workers"))
        assertEquals(StorageTypeModel.CACHE_STORAGE, StorageTypeModel.fromWire("cache_storage"))
        assertEquals(StorageTypeModel.INTEREST_GROUPS, StorageTypeModel.fromWire("interest_groups"))
        assertEquals(StorageTypeModel.ALL, StorageTypeModel.fromWire("all"))
        assertNull(StorageTypeModel.fromWire("bogus"))
        assertNull(StorageTypeModel.fromWire(null))
    }

    @Test
    fun storageActionResult_and_error_factories_produceExpectedValues() {
        val success = StorageActionResult.Success(Unit)
        assertTrue(success.value === Unit)

        val unsupported = StorageError.Unsupported("nope")
        assertEquals("nope", (unsupported as StorageError.Unsupported).reason)

        val unavailable = StorageError.Unavailable("not bound")
        assertEquals("not bound", (unavailable as StorageError.Unavailable).detail)

        val failed = StorageError.ExecutionFailed(RuntimeException("boom"))
        assertEquals("boom", (failed as StorageError.ExecutionFailed).cause.message)

        val failure = StorageActionResult.Failure(unsupported)
        assertTrue(failure.error === unsupported)
    }

    @Test
    fun parser_missingFields_parseSafely() {
        val usage = StorageParser.parseUsageAndQuota(mapOf<String, Any?>("usage" to 10L))
        assertNull(usage)
        assertNull(StorageParser.parseUsageAndQuota("not-a-map"))
        assertNull(StorageParser.parseUsageAndQuota(null))

        val full = StorageParser.parseUsageAndQuota(
            mapOf<String, Any?>("usage" to 10L, "quota" to 20L)
        )
        assertNotNull(full)
        assertEquals(10L, full!!.usage)
        assertEquals(20L, full!!.quota)
        assertTrue(full!!.usageBreakdown.isEmpty())

        assertNull(StorageParser.parseCacheContentUpdated(mapOf<String, Any?>()))
        assertNull(StorageParser.parseCacheContentUpdated("not-a-map"))
        assertNull(StorageParser.parseCacheListUpdated(mapOf<String, Any?>()))
        assertNull(StorageParser.parseCacheListUpdated("not-a-map"))
    }
}