package com.coppermux.cutools.core.storage

import com.coppermux.cutools.core.storage.models.IndexedDbDataEntriesModel
import com.coppermux.cutools.core.storage.models.IndexedDatabaseModel
import com.coppermux.cutools.core.storage.models.IndexedObjectStoreModel
import com.coppermux.cutools.core.storage.models.StorageIntrospectionActionResult
import com.coppermux.cutools.core.storage.models.StorageIntrospectionCapabilityStatus
import com.coppermux.cutools.core.storage.models.StorageIntrospectionError
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Sub-Phase 8C IndexedDB introspection engine.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the dispatcher → engine, proving `IndexedDB.enable` / `disable` /
 * `requestDatabaseNames` / `requestDatabase` / `requestData` /
 * `clearObjectStore` serialization and strict schema / entry parsing. No-Fake-
 * Capability is enforced: protocol failures, missing sessions, unavailable
 * methods, and connection loss map to explicit UNSUPPORTED / UNAVAILABLE /
 * FAILED / TARGET_DISCONNECTED outcomes, never fabricated database state.
 */
class IndexedDbEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: IndexedDbEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = IndexedDbEngine(dispatcher)
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
    fun enable_serializesMethodAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.enable { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("IndexedDB.enable") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun disable_serializesMethodAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.disable { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("IndexedDB.disable") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.disable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun requestDatabaseNames_serializesOriginAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        engine.requestDatabaseNames("https://example.com") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("IndexedDB.requestDatabaseNames") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.requestDatabaseNames\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun requestDatabase_serializesOriginDatabaseNameAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<IndexedDatabaseModel>>()
        engine.requestDatabase("https://example.com", "mydb") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("IndexedDB.requestDatabase") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.requestDatabase\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com\""))
        assertTrue(raw.contains("\"databaseName\":\"mydb\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun requestData_serializesPaginationParams() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<IndexedDbDataEntriesModel>>()
        engine.requestData("https://example.com", "mydb", "users", 10, 25) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("IndexedDB.requestData") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.requestData\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com\""))
        assertTrue(raw.contains("\"databaseName\":\"mydb\""))
        assertTrue(raw.contains("\"objectStoreName\":\"users\""))
        assertTrue(raw.contains("\"skipCount\":10"))
        assertTrue(raw.contains("\"pageSize\":25"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun clearObjectStore_serializesParams() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.clearObjectStore("https://example.com", "mydb", "users") { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("IndexedDB.clearObjectStore") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.clearObjectStore\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com\""))
        assertTrue(raw.contains("\"databaseName\":\"mydb\""))
        assertTrue(raw.contains("\"objectStoreName\":\"users\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ parsing ============

    @Test
    fun requestDatabaseNames_success_parsesNames() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        engine.requestDatabaseNames("https://example.com") { results.add(it) }
        deliverSuccess("IndexedDB.requestDatabaseNames", """{"databaseNames":["mydb","other"]}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageIntrospectionActionResult.Success)
        assertEquals(listOf("mydb", "other"), (results[0] as StorageIntrospectionActionResult.Success).value)
        assertEquals(StorageIntrospectionCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun requestDatabaseNames_missingNames_isEmpty() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        engine.requestDatabaseNames("https://example.com") { results.add(it) }
        deliverSuccess("IndexedDB.requestDatabaseNames", """{}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageIntrospectionActionResult.Success).value.isEmpty())
    }

    @Test
    fun requestDatabase_success_parsesSchemaWithStoresAndIndexes() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<IndexedDatabaseModel>>()
        engine.requestDatabase("https://example.com", "mydb") { results.add(it) }
        deliverSuccess(
            "IndexedDB.requestDatabase",
            """{"databaseWithObjectStores":{
                "name":"mydb","version":3,
                "objectStores":[
                    {"name":"users","keyPath":"id","autoIncrement":true,
                     "indexes":[{"name":"byName","keyPath":"name","unique":true,"multiEntry":false}]},
                    {"name":"log","keyPath":"","autoIncrement":false,"indexes":[]}
                ]
            }}""",
        )
        assertTrue(waitFor { results.size == 1 })
        val db = (results[0] as StorageIntrospectionActionResult.Success).value
        assertEquals("mydb", db.name)
        assertEquals(3, db.version)
        assertEquals(2, db.objectStores.size)
        val users = db.objectStores[0]
        assertEquals("users", users.name)
        assertEquals("id", users.keyPath)
        assertTrue(users.autoIncrement)
        assertEquals(1, users.indexes.size)
        assertEquals("byName", users.indexes[0].name)
        assertEquals("name", users.indexes[0].keyPath)
        assertTrue(users.indexes[0].unique)
        assertFalse(users.indexes[0].multiEntry)
        assertEquals("log", db.objectStores[1].name)
    }

    @Test
    fun requestDatabase_missingDatabaseWithObjectStores_isEmpty() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<IndexedDatabaseModel>>()
        engine.requestDatabase("https://example.com", "mydb") { results.add(it) }
        deliverSuccess("IndexedDB.requestDatabase", """{}""")
        assertTrue(waitFor { results.size == 1 })
        val db = (results[0] as StorageIntrospectionActionResult.Success).value
        assertEquals("", db.name)
        assertTrue(db.objectStores.isEmpty())
    }

    @Test
    fun requestData_success_parsesEntriesWithHasMore() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<IndexedDbDataEntriesModel>>()
        engine.requestData("https://example.com", "mydb", "users", 0, 10) { results.add(it) }
        deliverSuccess(
            "IndexedDB.requestData",
            """{"objectStoreDataEntries":[
                {"key":{"type":"number","value":"1"},"primaryKey":{"type":"number","value":"1"},"value":{"type":"object","value":"{\"name\":\"a\"}"}},
                {"key":{"type":"string","value":"k2"},"primaryKey":{"type":"string","value":"k2"},"value":{"type":"string","value":"raw"}}
            ],"hasMore":true}""",
        )
        assertTrue(waitFor { results.size == 1 })
        val page = (results[0] as StorageIntrospectionActionResult.Success).value
        assertEquals(2, page.entries.size)
        assertTrue(page.hasMore)
        assertEquals("1", page.entries[0].key)
        assertEquals("1", page.entries[0].primaryKey)
        assertEquals("{\"name\":\"a\"}", page.entries[0].value)
        assertEquals("k2", page.entries[1].key)
        assertEquals("raw", page.entries[1].value)
    }

    @Test
    fun requestData_missingObjectStoreDataEntries_isEmpty() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<IndexedDbDataEntriesModel>>()
        engine.requestData("https://example.com", "mydb", "users", 0, 10) { results.add(it) }
        deliverSuccess("IndexedDB.requestData", """{"hasMore":false}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageIntrospectionActionResult.Success).value.entries.isEmpty())
    }

    @Test
    fun requestDatabaseNames_nonObjectResult_isFailure() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        engine.requestDatabaseNames("https://example.com") { results.add(it) }
        deliverSuccess("IndexedDB.requestDatabaseNames", "42")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageIntrospectionActionResult.Failure)
        assertTrue((results[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.ExecutionFailed)
        assertEquals(StorageIntrospectionCapabilityStatus.FAILED, engine.capability)
    }

    // ============ domain/mutation success ============

    @Test
    fun enable_success_returnsSuccessAndCapability() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.enable { results.add(it) }
        deliverSuccess("IndexedDB.enable", "{}")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageIntrospectionActionResult.Success)
        assertEquals(StorageIntrospectionCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun disable_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.disable { results.add(it) }
        deliverSuccess("IndexedDB.disable", "{}")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageIntrospectionActionResult.Success)
    }

    @Test
    fun clearObjectStore_success_returnsSuccessAndCapability() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.clearObjectStore("https://example.com", "mydb", "users") { results.add(it) }
        deliverSuccess("IndexedDB.clearObjectStore", "{}")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is StorageIntrospectionActionResult.Success)
        assertEquals(StorageIntrospectionCapabilityStatus.SUPPORTED, engine.capability)
    }

    // ============ error mapping ============

    @Test
    fun withoutBoundSession_isUnavailableForAllCommands() {
        val enable = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.enable { enable.add(it) }
        assertTrue((enable[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unavailable)

        val disable = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.disable { disable.add(it) }
        assertTrue((disable[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unavailable)

        val names = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        engine.requestDatabaseNames("https://example.com") { names.add(it) }
        assertTrue((names[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unavailable)

        val schema = CopyOnWriteArrayList<StorageIntrospectionActionResult<IndexedDatabaseModel>>()
        engine.requestDatabase("https://example.com", "mydb") { schema.add(it) }
        assertTrue((schema[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unavailable)

        val data = CopyOnWriteArrayList<StorageIntrospectionActionResult<IndexedDbDataEntriesModel>>()
        engine.requestData("https://example.com", "mydb", "users", 0, 10) { data.add(it) }
        assertTrue((data[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unavailable)

        val clear = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.clearObjectStore("https://example.com", "mydb", "users") { clear.add(it) }
        assertTrue((clear[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unavailable)

        assertEquals(StorageIntrospectionCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun requestDatabaseNames_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        engine.requestDatabaseNames("https://example.com") { results.add(it) }
        deliverError("IndexedDB.requestDatabaseNames", -32601, "Method not found")
        assertTrue(waitFor { engine.capability == StorageIntrospectionCapabilityStatus.UNSUPPORTED })
        assertTrue((results[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.Unsupported)
    }

    @Test
    fun requestDatabase_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<IndexedDatabaseModel>>()
        engine.requestDatabase("https://example.com", "mydb") { results.add(it) }
        deliverError("IndexedDB.requestDatabase", -32000, "schema broke")
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as StorageIntrospectionActionResult.Failure
        assertTrue(failure.error is StorageIntrospectionError.ExecutionFailed)
        assertTrue((failure.error as StorageIntrospectionError.ExecutionFailed).cause.message.orEmpty().contains("schema broke"))
        assertEquals(StorageIntrospectionCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun requestData_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<IndexedDbDataEntriesModel>>()
        engine.requestData("https://example.com", "mydb", "users", 0, 10) { results.add(it) }
        deliverError("IndexedDB.requestData", -32000, "data broke")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.ExecutionFailed)
        assertEquals(StorageIntrospectionCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun clearObjectStore_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<Unit>>()
        engine.clearObjectStore("https://example.com", "mydb", "users") { results.add(it) }
        deliverError("IndexedDB.clearObjectStore", -32000, "clear failed")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.ExecutionFailed)
    }

    @Test
    fun requestDatabaseNames_afterConnectionLost_mapsToTargetDisconnected() {
        bind()
        transport.disconnect()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        engine.requestDatabaseNames("https://example.com") { results.add(it) }
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as StorageIntrospectionActionResult.Failure).error is StorageIntrospectionError.TargetDisconnected)
    }

    // ============ lifecycle / detach / target switch ============

    @Test
    fun detach_clearsState() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<IndexedDatabaseModel>>()
        engine.requestDatabase("https://example.com", "mydb") { results.add(it) }
        deliverSuccess("IndexedDB.requestDatabase", """{"databaseWithObjectStores":{"name":"mydb","version":1,"objectStores":[]}}""")
        assertTrue(waitFor { engine.capability == StorageIntrospectionCapabilityStatus.SUPPORTED })

        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        engine.requestDatabaseNames("https://example.com") { results.add(it) }
        deliverSuccess("IndexedDB.requestDatabaseNames", """{"databaseNames":["mydb"]}""")
        assertTrue(waitFor { engine.capability == StorageIntrospectionCapabilityStatus.SUPPORTED })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        engine.requestDatabaseNames("https://example.com") { results.add(it) }
        deliverSuccess("IndexedDB.requestDatabaseNames", """{"databaseNames":["mydb"]}""")
        assertTrue(waitFor { engine.capability == StorageIntrospectionCapabilityStatus.SUPPORTED })

        engine.onConnectionLost()
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<StorageIntrospectionActionResult<List<String>>>()
        engine.requestDatabaseNames("https://example.com") { results.add(it) }
        val id = parseIdFor("IndexedDB.requestDatabaseNames")

        engine.detach()
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"databaseNames":["mydb"]}}""")
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    // ============ model-level mapping ============

    @Test
    fun parser_missingFields_parseSafely() {
        assertEquals(emptyList<String>(), IndexedDbParser.parseDatabaseNames(null))
        assertEquals(emptyList<String>(), IndexedDbParser.parseDatabaseNames("not-a-map"))
        assertEquals(IndexedDatabaseModel(), IndexedDbParser.parseDatabase(null))
        assertEquals(IndexedDatabaseModel(), IndexedDbParser.parseDatabase("not-a-map"))
        assertEquals(IndexedDbDataEntriesModel(), IndexedDbParser.parseData(null))
        assertEquals(IndexedDbDataEntriesModel(), IndexedDbParser.parseData("not-a-map"))
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
    fun objectStoreAndEntryModels_haveSafeDefaults() {
        val store = IndexedObjectStoreModel()
        assertEquals("", store.name)
        assertEquals("", store.keyPath)
        assertFalse(store.autoIncrement)
        assertTrue(store.indexes.isEmpty())
    }
}
