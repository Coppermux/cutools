package com.coppermux.cutools.core.storage

import com.coppermux.cutools.core.storage.models.CacheEntryModel
import com.coppermux.cutools.core.storage.models.CacheStorageBucketModel
import com.coppermux.cutools.core.storage.models.StorageIntrospectionActionResult
import com.coppermux.cutools.core.storage.models.StorageIntrospectionCapabilityStatus
import com.coppermux.cutools.core.storage.models.StorageIntrospectionError
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Sub-Phase 8C CacheStorage introspection engine.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the dispatcher → engine, proving `CacheStorage.requestCacheNames` /
 * `requestEntries` / `deleteCache` / `deleteEntry` serialization and strict
 * bucket / entry parsing. No-Fake-Capability is enforced: protocol failures,
 * missing sessions, unavailable methods, and connection loss map to explicit
 * UNSUPPORTED / UNAVAILABLE / FAILED / TARGET_DISCONNECTED outcomes, never
 * fabricated cache state.
 */
class CacheStorageEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: CacheStorageEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = CacheStorageEngine(dispatcher)
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
    fun requestCacheNames_serializesOriginAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheStorageBucketModel>>>()
        engine.requestCacheNames("https://example.com") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("CacheStorage.requestCacheNames") }
        assertTrue(raw.contains("\"method\":\"CacheStorage.requestCacheNames\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun requestEntries_serializesPaginationParams() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheEntryModel>>>()
        engine.requestEntries("cache-1", 5, 20) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("CacheStorage.requestEntries") }
        assertTrue(raw.contains("\"method\":\"CacheStorage.requestEntries\""))
        assertTrue(raw.contains("\"cacheId\":\"cache-1\""))
        assertTrue(raw.contains("\"skipCount\":5"))
        assertTrue(raw.contains("\"pageSize\":20"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun deleteCache_serializesCacheId() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.deleteCache("cache-1") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("CacheStorage.deleteCache") }
        assertTrue(raw.contains("\"method\":\"CacheStorage.deleteCache\""))
        assertTrue(raw.contains("\"cacheId\":\"cache-1\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun deleteEntry_serializesCacheIdAndRequest() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.deleteEntry("cache-1", "https://example.com/data.json") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("CacheStorage.deleteEntry") }
        assertTrue(raw.contains("\"method\":\"CacheStorage.deleteEntry\""))
        assertTrue(raw.contains("\"cacheId\":\"cache-1\""))
        assertTrue(raw.contains("\"request\":\"https://example.com/data.json\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ parsing ============

    @Test
    fun requestCacheNames_success_parsesBuckets() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheStorageBucketModel>>>()
        engine.requestCacheNames("https://example.com") { results.add(it) }
        deliverSuccess(
            "CacheStorage.requestCacheNames",
            """{"caches":[
                {"cacheId":"c1","securityOrigin":"https://example.com","cacheName":"v1"},
                {"cacheId":"c2","securityOrigin":"https://example.com","cacheName":"v2"}
            ]}""",
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageIntrospectionActionResult.Success)
        val buckets = (results[0] as StorageIntrospectionActionResult.Success).value
        assertEquals(2, buckets.size)
        assertEquals("c1", buckets[0].cacheId)
        assertEquals("https://example.com", buckets[0].securityOrigin)
        assertEquals("v1", buckets[0].cacheName)
        assertEquals("c2", buckets[1].cacheId)
        assertEquals("v2", buckets[1].cacheName)
        assertEquals(StorageIntrospectionCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun requestCacheNames_missingCaches_isEmpty() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheStorageBucketModel>>>()
        engine.requestCacheNames("https://example.com") { results.add(it) }
        deliverSuccess("CacheStorage.requestCacheNames", """{}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageIntrospectionActionResult.Success).value.isEmpty())
    }

    @Test
    fun requestEntries_success_parsesRequestAndResponse() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheEntryModel>>>()
        engine.requestEntries("cache-1", 0, 10) { results.add(it) }
        deliverSuccess(
            "CacheStorage.requestEntries",
            """{"cacheDataEntries":[
                {
                    "requestURL":"https://example.com/data.json",
                    "requestMethod":"GET",
                    "requestHeaders":[{"name":"Accept"},{"name":"Accept-Language"}],
                    "responseStatus":200,
                    "responseStatusText":"OK",
                    "responseHeaders":[{"name":"Content-Type"},{"name":"Cache-Control"}]
                }
            ],"returnCount":1}""",
        )
        assertTrue(waitFor { results.size == 1 })
        val entries = (results[0] as StorageIntrospectionActionResult.Success).value
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("https://example.com/data.json", entry.requestURL)
        assertEquals(listOf("Accept", "Accept-Language"), entry.requestHeaders)
        assertEquals(200, entry.responseStatus)
        assertEquals("OK", entry.responseStatusText)
        assertEquals(listOf("Content-Type", "Cache-Control"), entry.responseHeaders)
    }

    @Test
    fun requestEntries_missingEntries_isEmpty() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheEntryModel>>>()
        engine.requestEntries("cache-1", 0, 10) { results.add(it) }
        deliverSuccess("CacheStorage.requestEntries", """{"returnCount":0}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageIntrospectionActionResult.Success).value.isEmpty())
    }

    @Test
    fun requestCacheNames_nonObjectResult_isFailure() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheStorageBucketModel>>>()
        engine.requestCacheNames("https://example.com") { results.add(it) }
        deliverSuccess("CacheStorage.requestCacheNames", "42")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageIntrospectionActionResult.Failure)
        assertTrue((results[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.ExecutionFailed)
        assertEquals(StorageIntrospectionCapabilityStatus.FAILED, engine.capability)
    }

    // ============ mutation success ============

    @Test
    fun deleteCache_success_returnsSuccessAndCapability() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.deleteCache("cache-1") { results.add(it) }
        deliverSuccess("CacheStorage.deleteCache", "{}")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageIntrospectionActionResult.Success)
        assertEquals(StorageIntrospectionCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun deleteEntry_success_returnsSuccessAndCapability() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.deleteEntry("cache-1", "https://example.com/data.json") { results.add(it) }
        deliverSuccess("CacheStorage.deleteEntry", "{}")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageIntrospectionActionResult.Success)
        assertEquals(StorageIntrospectionCapabilityStatus.SUPPORTED, engine.capability)
    }

    // ============ error mapping ============

    @Test
    fun withoutBoundSession_isUnavailableForAllCommands() {
        val names = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheStorageBucketModel>>>()
        engine.requestCacheNames("https://example.com") { names.add(it) }
        assertTrue((names[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unavailable)

        val entries = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheEntryModel>>>()
        engine.requestEntries("cache-1", 0, 10) { entries.add(it) }
        assertTrue((entries[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unavailable)

        val delCache = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.deleteCache("cache-1") { delCache.add(it) }
        assertTrue((delCache[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unavailable)

        val delEntry = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.deleteEntry("cache-1", "https://example.com/x") { delEntry.add(it) }
        assertTrue((delEntry[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unavailable)

        assertEquals(StorageIntrospectionCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun requestCacheNames_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheStorageBucketModel>>>()
        engine.requestCacheNames("https://example.com") { results.add(it) }
        deliverError("CacheStorage.requestCacheNames", -32601, "Method not found")
        assertTrue(waitFor { engine.capability == StorageIntrospectionCapabilityStatus.UNSUPPORTED })
        assertTrue((results[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unsupported)
    }

    @Test
    fun requestEntries_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheEntryModel>>>()
        engine.requestEntries("cache-1", 0, 10) { results.add(it) }
        deliverError("CacheStorage.requestEntries", -32000, "entries broke")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.ExecutionFailed)
        assertEquals(StorageIntrospectionCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun deleteCache_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.deleteCache("cache-1") { results.add(it) }
        deliverError("CacheStorage.deleteCache", -32000, "delete broke")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.ExecutionFailed)
    }

    @Test
    fun deleteEntry_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.deleteEntry("cache-1", "https://example.com/x") { results.add(it) }
        deliverError("CacheStorage.deleteEntry", -32000, "delete broke")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.ExecutionFailed)
    }

    @Test
    fun requestCacheNames_afterConnectionLost_mapsToTargetDisconnected() {
        bind()
        transport.disconnect()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheStorageBucketModel>>>()
        engine.requestCacheNames("https://example.com") { results.add(it) }
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.TargetDisconnected)
    }

    // ============ lifecycle / detach / target switch ============

    @Test
    fun detach_clearsState() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheStorageBucketModel>>>()
        engine.requestCacheNames("https://example.com") { results.add(it) }
        deliverSuccess("CacheStorage.requestCacheNames", """{"caches":[{"cacheId":"c1","cacheName":"v"}]}""")
        assertTrue(waitFor { engine.capability == StorageIntrospectionCapabilityStatus.SUPPORTED })

        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheStorageBucketModel>>>()
        engine.requestCacheNames("https://example.com") { results.add(it) }
        deliverSuccess("CacheStorage.requestCacheNames", """{"caches":[{"cacheId":"c1","cacheName":"v"}]}""")
        assertTrue(waitFor { engine.capability == StorageIntrospectionCapabilityStatus.SUPPORTED })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheStorageBucketModel>>>()
        engine.requestCacheNames("https://example.com") { results.add(it) }
        deliverSuccess("CacheStorage.requestCacheNames", """{"caches":[{"cacheId":"c1","cacheName":"v"}]}""")
        assertTrue(waitFor { engine.capability == StorageIntrospectionCapabilityStatus.SUPPORTED })

        engine.onConnectionLost()
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<CacheStorageBucketModel>>>()
        engine.requestCacheNames("https://example.com") { results.add(it) }
        val id = parseIdFor("CacheStorage.requestCacheNames")

        engine.detach()
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"caches":[{"cacheId":"c1","cacheName":"v"}]}}""")
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    // ============ model-level mapping ============

    @Test
    fun parser_missingFields_parseSafely() {
        assertEquals(emptyList<CacheStorageBucketModel>(), CacheStorageParser.parseBuckets(null))
        assertEquals(emptyList<CacheStorageBucketModel>(), CacheStorageParser.parseBuckets("not-a-map"))
        assertEquals(emptyList<CacheEntryModel>(), CacheStorageParser.parseEntries(null))
        assertEquals(emptyList<CacheEntryModel>(), CacheStorageParser.parseEntries("not-a-map"))
    }

    @Test
    fun introspectionResult_and_error_factories_produceExpectedValues() {
        val success = StorageIntrospectionActionResult.Success(Unit)
        assertTrue(success.value === Unit)

        val unsupported = StorageIntrospectionError.Unsupported("nope")
        assertEquals("nope", (unsupported as StorageIntrospectionError.Unsupported).reason)

        val unavailable = StorageIntrospectionError.Unavailable("not bound")
        assertEquals("not bound", (unavailable as StorageIntrospectionError.Unavailable).detail)

        val failed = StorageIntrospectionError.ExecutionFailed(RuntimeException("boom"))
        assertEquals("boom", (failed as StorageIntrospectionError.ExecutionFailed).cause.message)

        val failure = StorageIntrospectionActionResult.Failure(unsupported)
        assertTrue(failure.error === unsupported)
    }

    @Test
    fun cacheEntryModel_hasSafeDefaults() {
        val entry = CacheEntryModel()
        assertEquals("", entry.requestURL)
        assertEquals(0, entry.responseStatus)
        assertTrue(entry.requestHeaders.isEmpty())
        assertTrue(entry.responseHeaders.isEmpty())
    }
}
