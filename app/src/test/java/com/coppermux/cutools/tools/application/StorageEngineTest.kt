package com.coppermux.cutools.tools.application

import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.tools.application.engine.StorageEngine
import com.coppermux.cutools.tools.application.models.StorageActionStatus
import com.coppermux.cutools.tools.application.models.StorageActionResult
import com.coppermux.cutools.tools.application.models.StorageCapabilityStatus
import com.coppermux.cutools.tools.application.models.StorageType
import com.coppermux.cutools.tools.application.models.StorageUsageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Sub-Phase 6A Storage Foundation.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → dispatcher → engine, proving `Storage.getUsageAndQuota` /
 * `Storage.clearDataForOrigin` serialization and genuine response parsing in the
 * same pipeline the app uses against Chrome. No-Fake-Capability is enforced:
 * protocol failures map to explicit UNSUPPORTED / UNAVAILABLE / FAILED outcomes.
 */
class StorageEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: StorageEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        val router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = StorageEngine(dispatcher)
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

    // ============ payload generation ============

    @Test
    fun getUsageAndQuota_serializesOriginParamAndSessionId() {
        bind()
        engine.getUsageAndQuota("https://example.com/") {}
        val raw = transport.sentMessages.last { it.contains("Storage.getUsageAndQuota") }
        assertTrue(raw.contains("\"method\":\"Storage.getUsageAndQuota\""))
        assertTrue(raw.contains("\"origin\":\"https://example.com/\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun clearDataForOrigin_singleType_serializesPayload() {
        bind()
        engine.clearDataForOrigin("https://example.com/", listOf(StorageType.COOKIES)) {}
        val raw = transport.sentMessages.last { it.contains("Storage.clearDataForOrigin") }
        assertTrue(raw.contains("\"method\":\"Storage.clearDataForOrigin\""))
        assertTrue(raw.contains("\"origin\":\"https://example.com/\""))
        assertTrue(raw.contains("\"storageTypes\":\"cookies\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun clearDataForOrigin_multipleTypes_serializesCommaJoinedList() {
        bind()
        engine.clearDataForOrigin(
            "https://example.com/",
            listOf(StorageType.LOCAL_STORAGE, StorageType.INDEXEDDB, StorageType.SERVICE_WORKERS, StorageType.CACHE_STORAGE),
        ) {}
        val raw = transport.sentMessages.last { it.contains("Storage.clearDataForOrigin") }
        assertTrue(raw.contains("\"storageTypes\":\"local_storage,indexeddb,service_workers,cache_storage\""))
    }

    @Test
    fun clearDataForOrigin_allType_serializesAsAll() {
        bind()
        engine.clearDataForOrigin("https://example.com/", listOf(StorageType.ALL)) {}
        val raw = transport.sentMessages.last { it.contains("Storage.clearDataForOrigin") }
        assertTrue(raw.contains("\"storageTypes\":\"all\""))
    }

    // ============ usage & quota parsing ============

    @Test
    fun getUsageAndQuota_success_parsesUsageQuotaAndBreakdown() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageModel>>()
        engine.getUsageAndQuota("https://example.com/") { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"usage":12345,"quota":999999,"usageBreakdown":[{"storageType":"local_storage","usage":10000},{"storageType":"indexeddb","usage":2345}]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val result = results[0]
        assertEquals(StorageActionStatus.SUCCESS, result.status)
        val usage = result.value!!
        assertEquals("https://example.com/", usage.origin)
        assertEquals(12345L, usage.usageBytes)
        assertEquals(999999L, usage.quotaBytes)
        assertEquals(2, usage.usageBreakdown.size)
        assertEquals(StorageType.LOCAL_STORAGE, usage.usageBreakdown[0].storageType)
        assertEquals(10000L, usage.usageBreakdown[0].usageBytes)
        assertEquals(StorageType.INDEXEDDB, usage.usageBreakdown[1].storageType)
        assertEquals(2345L, usage.usageBreakdown[1].usageBytes)
        assertTrue(usage.retrievedAt > 0)
        assertEquals(usage, engine.latestUsage)
        assertEquals(StorageCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun getUsageAndQuota_breakdown_mapsEveryCdpStorageType() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageModel>>()
        engine.getUsageAndQuota("https://example.com/") { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"usage":0,"quota":0,"usageBreakdown":[
                {"storageType":"appcache","usage":1},
                {"storageType":"cookies","usage":2},
                {"storageType":"file_systems","usage":3},
                {"storageType":"indexeddb","usage":4},
                {"storageType":"local_storage","usage":5},
                {"storageType":"shader_cache","usage":6},
                {"storageType":"websql","usage":7},
                {"storageType":"service_workers","usage":8},
                {"storageType":"cache_storage","usage":9},
                {"storageType":"other","usage":10},
                {"storageType":"all","usage":11}
            ]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val breakdown = results[0].value!!.usageBreakdown
        val expected = listOf(
            StorageType.APPCACHE, StorageType.COOKIES, StorageType.FILE_SYSTEMS,
            StorageType.INDEXEDDB, StorageType.LOCAL_STORAGE, StorageType.SHADER_CACHE,
            StorageType.WEBSQL, StorageType.SERVICE_WORKERS, StorageType.CACHE_STORAGE,
            StorageType.OTHER, StorageType.ALL,
        )
        assertEquals(expected, breakdown.map { it.storageType })
        assertEquals(1L, breakdown[0].usageBytes)
        assertEquals(11L, breakdown[10].usageBytes)
    }

    @Test
    fun getUsageAndQuota_unknownWireType_mapsToOther() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageModel>>()
        engine.getUsageAndQuota("https://example.com/") { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"usage":42,"quota":100,"usageBreakdown":[{"storageType":"brand_new_store","usage":7}]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val breakdown = results[0].value!!.usageBreakdown
        assertEquals(1, breakdown.size)
        assertEquals(StorageType.OTHER, breakdown[0].storageType)
        assertEquals(7L, breakdown[0].usageBytes)
    }

    @Test
    fun getUsageAndQuota_missingBreakdown_parsesWithEmptyList() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageModel>>()
        engine.getUsageAndQuota("https://example.com/") { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"usage":42,"quota":100}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(StorageActionStatus.SUCCESS, results[0].status)
        assertTrue(results[0].value!!.usageBreakdown.isEmpty())
    }

    @Test
    fun getUsageAndQuota_missingUsageField_failsExplicitly() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageModel>>()
        engine.getUsageAndQuota("https://example.com/") { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"quota":100}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(StorageActionStatus.FAILED, results[0].status)
        assertNull(results[0].value)
        assertNull(engine.latestUsage)
        assertEquals(StorageCapabilityStatus.FAILED, engine.capability)
    }

    // ============ error mapping ============

    @Test
    fun getUsageAndQuota_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageModel>>()
        engine.getUsageAndQuota("https://example.com/") { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"something broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(StorageActionStatus.FAILED, results[0].status)
        assertTrue(results[0].message.orEmpty().contains("something broke"))
        assertTrue(results[0].throwable != null)
        assertNull(engine.latestUsage)
        assertEquals(StorageCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun getUsageAndQuota_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageModel>>()
        engine.getUsageAndQuota("https://example.com/") { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(StorageActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(StorageCapabilityStatus.UNSUPPORTED, engine.capability)
        assertNull(engine.latestUsage)
    }

    @Test
    fun getUsageAndQuota_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageModel>>()
        engine.getUsageAndQuota("https://example.com/") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(StorageActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(StorageCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun clearDataForOrigin_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin("https://example.com/", listOf(StorageType.COOKIES)) { results.add(it) }
        val id = parseIdFor("Storage.clearDataForOrigin")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"clear failed"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(StorageActionStatus.FAILED, results[0].status)
        assertEquals(StorageCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun clearDataForOrigin_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin("https://example.com/", listOf(StorageType.INDEXEDDB)) { results.add(it) }
        val id = parseIdFor("Storage.clearDataForOrigin")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(StorageActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(StorageCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    @Test
    fun clearDataForOrigin_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin("https://example.com/", listOf(StorageType.COOKIES)) { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(StorageActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun clearDataForOrigin_emptyTypes_failsExplicitly() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin("https://example.com/", emptyList()) { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(StorageActionStatus.FAILED, results[0].status)
        assertTrue(results[0].message.orEmpty().contains("No storage types"))
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun clearDataForOrigin_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<Unit>>()
        engine.clearDataForOrigin("https://example.com/", listOf(StorageType.COOKIES)) { results.add(it) }
        val id = parseIdFor("Storage.clearDataForOrigin")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(StorageActionStatus.SUCCESS, results[0].status)
        assertEquals(StorageCapabilityStatus.SUPPORTED, engine.capability)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsUsageState() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageModel>>()
        engine.getUsageAndQuota("https://example.com/") { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"usage":5,"quota":10,"usageBreakdown":[]}}"""
        )
        assertTrue(waitFor { engine.latestUsage != null })

        engine.detach()
        assertNull(engine.session)
        assertNull(engine.latestUsage)
        assertNull(engine.capability)
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageModel>>()
        engine.getUsageAndQuota("https://example.com/") { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"usage":5,"quota":10,"usageBreakdown":[]}}"""
        )
        assertTrue(waitFor { engine.latestUsage != null })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.latestUsage)
        assertNull(engine.capability)
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        engine.onConnectionLost()
        assertNull(engine.session)
        assertNull(engine.latestUsage)
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<StorageActionResult<StorageUsageModel>>()
        engine.getUsageAndQuota("https://example.com/") { results.add(it) }
        val id = parseIdFor("Storage.getUsageAndQuota")

        engine.detach()
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"usage":99,"quota":100,"usageBreakdown":[]}}"""
        )
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.latestUsage)
        assertNull(engine.session)
    }

    // ============ model-level mapping ============

    @Test
    fun storageType_fromWire_mapsKnownValuesAndFallsBackToOther() {
        assertEquals(StorageType.COOKIES, StorageType.fromWire("cookies"))
        assertEquals(StorageType.INDEXEDDB, StorageType.fromWire("indexeddb"))
        assertEquals(StorageType.LOCAL_STORAGE, StorageType.fromWire("local_storage"))
        assertEquals(StorageType.SERVICE_WORKERS, StorageType.fromWire("service_workers"))
        assertEquals(StorageType.CACHE_STORAGE, StorageType.fromWire("cache_storage"))
        assertEquals(StorageType.ALL, StorageType.fromWire("all"))
        assertEquals(StorageType.OTHER, StorageType.fromWire(null))
        assertEquals(StorageType.OTHER, StorageType.fromWire("no_such_type"))
    }

    @Test
    fun storageActionResult_factories_produceExpectedStatuses() {
        val success = StorageActionResult.success(Unit)
        assertEquals(StorageActionStatus.SUCCESS, success.status)
        assertTrue(success.isSuccess)

        val unsupported = StorageActionResult.unsupported<String>("nope", RuntimeException("cause"))
        assertEquals(StorageActionStatus.UNSUPPORTED, unsupported.status)
        assertEquals("nope", unsupported.message)
        assertTrue(unsupported.throwable != null)

        val unavailable = StorageActionResult.unavailable<Int>("not bound")
        assertEquals(StorageActionStatus.UNAVAILABLE, unavailable.status)

        val failed = StorageActionResult.failed<Long>("boom")
        assertEquals(StorageActionStatus.FAILED, failed.status)
        assertTrue(!failed.isSuccess)
    }
}