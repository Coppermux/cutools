package com.coppermux.cutools.tools.application

import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import com.coppermux.cutools.tools.application.engine.IndexedDbEngine
import com.coppermux.cutools.tools.application.models.IndexedDbActionStatus
import com.coppermux.cutools.tools.application.models.IndexedDbActionResult
import com.coppermux.cutools.tools.application.models.IndexedDbCapabilityStatus
import com.coppermux.cutools.tools.application.models.IndexedDbDatabaseModel
import com.coppermux.cutools.tools.application.models.IndexedDbDataEntryModel
import com.coppermux.cutools.tools.application.models.IndexedDbIndexModel
import com.coppermux.cutools.tools.application.models.IndexedDbObjectStoreModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Sub-Phase 6D IndexedDB inspection.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → dispatcher → engine, proving `IndexedDB.enable`/`disable`,
 * `requestDatabaseNames`, `requestDatabase`, `requestData`, `clearObjectStore`,
 * and `deleteDatabase` serialization and genuine response parsing in the same
 * pipeline the app uses against Chrome. No-Fake-Capability is enforced: protocol
 * failures, missing sessions, and unavailable methods map to explicit
 * UNSUPPORTED / UNAVAILABLE / FAILED outcomes, never fabricated database schema.
 */
class IndexedDbEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: IndexedDbEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        val router = CdpEventRouter()
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

    // ============ payload generation ============

    @Test
    fun enable_serializesMethodAndSessionId() {
        bind()
        engine.enable {}
        val raw = transport.sentMessages.last { it.contains("IndexedDB.enable") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun disable_serializesMethodAndSessionId() {
        bind()
        engine.disable {}
        val raw = transport.sentMessages.last { it.contains("IndexedDB.disable") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.disable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun getDatabaseNames_serializesOriginAndSessionId() {
        bind()
        engine.getDatabaseNames("https://example.com/") {}
        val raw = transport.sentMessages.last { it.contains("IndexedDB.requestDatabaseNames") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.requestDatabaseNames\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com/\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun getDatabaseSchema_serializesOriginDatabaseNameAndSessionId() {
        bind()
        engine.getDatabaseSchema("https://example.com/", "db1") {}
        val raw = transport.sentMessages.last { it.contains("IndexedDB.requestDatabase") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.requestDatabase\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com/\""))
        assertTrue(raw.contains("\"databaseName\":\"db1\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun getEntries_serializesSkipLimitAndSessionId() {
        bind()
        engine.getEntries("https://example.com/", "db1", "store1", 5, 100) {}
        val raw = transport.sentMessages.last { it.contains("IndexedDB.requestData") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.requestData\""))
        assertTrue(raw.contains("\"objectStoreName\":\"store1\""))
        assertTrue(raw.contains("\"skipCount\":5"))
        assertTrue(raw.contains("\"pageSize\":100"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun clearObjectStore_serializesParamsAndSessionId() {
        bind()
        engine.clearObjectStore("https://example.com/", "db1", "store1") {}
        val raw = transport.sentMessages.last { it.contains("IndexedDB.clearObjectStore") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.clearObjectStore\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com/\""))
        assertTrue(raw.contains("\"databaseName\":\"db1\""))
        assertTrue(raw.contains("\"objectStoreName\":\"store1\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun deleteDatabase_serializesParamsAndSessionId() {
        bind()
        engine.deleteDatabase("https://example.com/", "db1") {}
        val raw = transport.sentMessages.last { it.contains("IndexedDB.deleteDatabase") }
        assertTrue(raw.contains("\"method\":\"IndexedDB.deleteDatabase\""))
        assertTrue(raw.contains("\"securityOrigin\":\"https://example.com/\""))
        assertTrue(raw.contains("\"databaseName\":\"db1\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    // ============ parsing ============

    @Test
    fun getDatabaseNames_success_parsesDatabaseNames() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<List<String>>>()
        engine.getDatabaseNames("https://example.com/") { results.add(it) }
        val id = parseIdFor("IndexedDB.requestDatabaseNames")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"databaseNames":["db1","db2"]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.SUCCESS, results[0].status)
        assertEquals(listOf("db1", "db2"), results[0].value!!)
        assertEquals(IndexedDbCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun getDatabaseSchema_success_parsesSchemaAndIndexes() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<IndexedDbDatabaseModel>>()
        engine.getDatabaseSchema("https://example.com/", "db1") { results.add(it) }
        val id = parseIdFor("IndexedDB.requestDatabase")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"databaseWithObjectStores":{
                "name":"db1","version":3,
                "objectStores":[
                    {"name":"users","keyPath":"id","autoIncrement":true,"indexes":[
                        {"name":"emailIdx","keyPath":"email","unique":true,"multiEntry":false},
                        {"name":"tagsIdx","keyPath":"tags","unique":false,"multiEntry":true}
                    ]},
                    {"name":"sessions","keyPath":"token","autoIncrement":false,"indexes":[]}
                ]
            }}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.SUCCESS, results[0].status)
        val db = results[0].value!!
        assertEquals("db1", db.name)
        assertEquals(3, db.version)
        assertEquals(2, db.objectStores.size)

        val users = db.objectStores[0]
        assertEquals("users", users.name)
        assertEquals("id", users.keyPath)
        assertTrue(users.autoIncrement)
        assertEquals(2, users.indexes.size)
        val emailIdx = users.indexes[0]
        assertEquals("emailIdx", emailIdx.name)
        assertEquals("email", emailIdx.keyPath)
        assertTrue(emailIdx.unique)
        assertTrue(!emailIdx.multiEntry)
        assertTrue(users.indexes[1].multiEntry)

        val sessions = db.objectStores[1]
        assertEquals("sessions", sessions.name)
        assertEquals("token", sessions.keyPath)
        assertTrue(!sessions.autoIncrement)
        assertTrue(sessions.indexes.isEmpty())
    }

    @Test
    fun getDatabaseSchema_missingObjectStore_returnsEmptyDatabase() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<IndexedDbDatabaseModel>>()
        engine.getDatabaseSchema("https://example.com/", "db1") { results.add(it) }
        val id = parseIdFor("IndexedDB.requestDatabase")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.SUCCESS, results[0].status)
        assertTrue(results[0].value!!.objectStores.isEmpty())
        assertEquals(IndexedDbCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun getEntries_success_parsesKeysAndPayloadJson() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<List<IndexedDbDataEntryModel>>>()
        engine.getEntries("https://example.com/", "db1", "users", 0, 50) { results.add(it) }
        val id = parseIdFor("IndexedDB.requestData")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"objectStoreData":[
                {"key":{"value":"1","baseId":"b1"},"primaryKey":{"value":"1","baseId":"b1"},"value":{"value":"{\"name\":\"Alice\",\"age\":30}","baseId":"v1"}},
                {"key":{"value":"2","baseId":"b1"},"primaryKey":{"value":"2","baseId":"b1"},"value":{"value":"{\"name\":\"Bob\",\"age\":25}","baseId":"v2"}}
            ]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.SUCCESS, results[0].status)
        val entries = results[0].value!!
        assertEquals(2, entries.size)

        assertEquals("1", entries[0].key)
        assertEquals("1", entries[0].primaryKey)
        assertEquals("{\"name\":\"Alice\",\"age\":30}", entries[0].value)

        assertEquals("2", entries[1].key)
        assertEquals("2", entries[1].primaryKey)
        assertEquals("{\"name\":\"Bob\",\"age\":25}", entries[1].value)
    }

    @Test
    fun getEntries_missingObjectStoreData_returnsEmptyList() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<List<IndexedDbDataEntryModel>>>()
        engine.getEntries("https://example.com/", "db1", "users", 0, 10) { results.add(it) }
        val id = parseIdFor("IndexedDB.requestData")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.SUCCESS, results[0].status)
        assertTrue(results[0].value!!.isEmpty())
    }

    @Test
    fun getDatabaseNames_missingResultObject_failsExplicitly() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<List<String>>>()
        engine.getDatabaseNames("https://example.com/") { results.add(it) }
        val id = parseIdFor("IndexedDB.requestDatabaseNames")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":42}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.FAILED, results[0].status)
        assertNull(results[0].value)
        assertEquals(IndexedDbCapabilityStatus.FAILED, engine.capability)
    }

    // ============ error mapping ============

    @Test
    fun getDatabaseNames_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<IndexedDbActionResult<List<String>>>()
        engine.getDatabaseNames("https://example.com/") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(IndexedDbActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(IndexedDbCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getDatabaseSchema_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<IndexedDbActionResult<IndexedDbDatabaseModel>>()
        engine.getDatabaseSchema("https://example.com/", "db1") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(IndexedDbActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getEntries_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<IndexedDbActionResult<List<IndexedDbDataEntryModel>>>()
        engine.getEntries("https://example.com/", "db1", "users", 0, 10) { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(IndexedDbActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun clearObjectStore_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<IndexedDbActionResult<Unit>>()
        engine.clearObjectStore("https://example.com/", "db1", "users") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(IndexedDbActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun deleteDatabase_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<IndexedDbActionResult<Unit>>()
        engine.deleteDatabase("https://example.com/", "db1") { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(IndexedDbActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun disable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<IndexedDbActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertEquals(1, results.size)
        assertEquals(IndexedDbActionStatus.UNAVAILABLE, results[0].status)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getDatabaseNames_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<List<String>>>()
        engine.getDatabaseNames("https://example.com/") { results.add(it) }
        val id = parseIdFor("IndexedDB.requestDatabaseNames")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"indexeddb read failed"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.FAILED, results[0].status)
        assertTrue(results[0].message.orEmpty().contains("indexeddb read failed"))
        assertEquals(IndexedDbCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun getDatabaseSchema_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<IndexedDbDatabaseModel>>()
        engine.getDatabaseSchema("https://example.com/", "db1") { results.add(it) }
        val id = parseIdFor("IndexedDB.requestDatabase")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(IndexedDbCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    @Test
    fun getEntries_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<List<IndexedDbDataEntryModel>>>()
        engine.getEntries("https://example.com/", "db1", "users", 0, 10) { results.add(it) }
        val id = parseIdFor("IndexedDB.requestData")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"request data failed"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.FAILED, results[0].status)
        assertEquals(IndexedDbCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun clearObjectStore_methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<Unit>>()
        engine.clearObjectStore("https://example.com/", "db1", "users") { results.add(it) }
        val id = parseIdFor("IndexedDB.clearObjectStore")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.UNSUPPORTED, results[0].status)
        assertEquals(IndexedDbCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    @Test
    fun deleteDatabase_protocolFailure_mapsToFailed() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<Unit>>()
        engine.deleteDatabase("https://example.com/", "db1") { results.add(it) }
        val id = parseIdFor("IndexedDB.deleteDatabase")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"delete broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.FAILED, results[0].status)
        assertEquals(IndexedDbCapabilityStatus.FAILED, engine.capability)
    }

    // ============ mutation success ============

    @Test
    fun enable_success_returnsSuccessAndUpdatesCapability() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<Unit>>()
        engine.enable { results.add(it) }
        val id = parseIdFor("IndexedDB.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.SUCCESS, results[0].status)
        assertEquals(IndexedDbCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun disable_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<Unit>>()
        engine.disable { results.add(it) }
        val id = parseIdFor("IndexedDB.disable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.SUCCESS, results[0].status)
    }

    @Test
    fun clearObjectStore_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<Unit>>()
        engine.clearObjectStore("https://example.com/", "db1", "users") { results.add(it) }
        val id = parseIdFor("IndexedDB.clearObjectStore")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.SUCCESS, results[0].status)
        assertEquals(IndexedDbCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun deleteDatabase_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<Unit>>()
        engine.deleteDatabase("https://example.com/", "db1") { results.add(it) }
        val id = parseIdFor("IndexedDB.deleteDatabase")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertEquals(IndexedDbActionStatus.SUCCESS, results[0].status)
        assertEquals(IndexedDbCapabilityStatus.SUPPORTED, engine.capability)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsSessionAndCapability() {
        bind()
        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        engine.enable {}
        val id = parseIdFor("IndexedDB.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.capability == IndexedDbCapabilityStatus.SUPPORTED })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        engine.onConnectionLost()
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<IndexedDbActionResult<List<String>>>()
        engine.getDatabaseNames("https://example.com/") { results.add(it) }
        val id = parseIdFor("IndexedDB.requestDatabaseNames")

        engine.detach()
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"databaseNames":["db1"]}}""")
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
        assertNull(engine.capability)
    }

    // ============ model-level mapping ============

    @Test
    fun objectStoreModel_parsesWellFormedFields() {
        val store = IndexedDbObjectStoreModel(
            name = "users",
            keyPath = "id",
            autoIncrement = true,
            indexes = listOf(IndexedDbIndexModel(name = "email", keyPath = "email", unique = true, multiEntry = false)),
        )
        assertEquals("users", store.name)
        assertEquals("id", store.keyPath)
        assertTrue(store.autoIncrement)
        assertTrue(store.indexes[0].unique)
        assertTrue(!store.indexes[0].multiEntry)
    }

    @Test
    fun indexedDbDataEntryModel_defaultsAreSafe() {
        val entry = IndexedDbDataEntryModel()
        assertEquals("", entry.key)
        assertEquals("", entry.primaryKey)
        assertEquals("", entry.value)
    }
}