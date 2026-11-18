package com.coppermux.cutools.core.transport

import com.coppermux.cutools.core.error.CommandTimeoutException
import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.error.ProtocolException
import com.coppermux.cutools.core.error.TransportException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class CommandDispatcherTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var eventRouter: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        eventRouter = CdpEventRouter()
        val executor = Executors.newSingleThreadScheduledExecutor()
        val config = DispatcherConfig(defaultTimeoutMs = 5000, executor = executor)
        dispatcher = CommandDispatcher(transport, eventRouter, config)
        // Connect with the dispatcher as listener so messages flow through
        transport.connect("ws://localhost:9222", dispatcher)
    }

    // ============== Command ID generation ==============

    @Test
    fun commandIds_areUniqueAndIncrementing() {
        val ids = mutableSetOf<Long>()
        repeat(10) {
            dispatcher.sendCommand("Test.method$it") { }
        }
        val sent = transport.sentMessages
        assertEquals(10, sent.size)
        sent.forEach { json ->
            val id = extractId(json)
            assertTrue("ID $id already seen", ids.add(id))
        }
    }

    // ============== Multiple pending commands ==============

    @Test
    fun sendCommand_multiplePendingCommands_allAwaitResponses() {
        val results = mutableMapOf<Long, CommandResult>()

        dispatcher.sendCommand("Method.one") { results[1L] = it }
        dispatcher.sendCommand("Method.two") { results[2L] = it }
        dispatcher.sendCommand("Method.three") { results[3L] = it }

        assertEquals(3, dispatcher.pendingCommandCount)

        // Send responses in different order
        transport.deliverMessage("""{"id":3,"result":{"value":30}}""")
        transport.deliverMessage("""{"id":1,"result":{"value":10}}""")
        transport.deliverMessage("""{"id":2,"result":{"value":20}}""")

        assertEquals(0, dispatcher.pendingCommandCount)
        assertTrue(results[1L] is CommandResult.Success)
        assertTrue(results[2L] is CommandResult.Success)
        assertTrue(results[3L] is CommandResult.Success)
    }

    // ============== Out-of-order responses ==============

    @Test
    fun outOfOrderResponses_correlateCorrectly() {
        val firstResult = AtomicReference<CommandResult?>()
        val secondResult = AtomicReference<CommandResult?>()

        dispatcher.sendCommand("Method.first") { firstResult.set(it) }
        val id1 = extractId(transport.sentMessages.last())
        dispatcher.sendCommand("Method.second") { secondResult.set(it) }
        val id2 = extractId(transport.sentMessages.last())

        assertTrue(id1 < id2)

        // Respond to second first, then first
        transport.deliverMessage("""{"id":$id2,"result":{"data":"second"}}""")
        transport.deliverMessage("""{"id":$id1,"result":{"data":"first"}}""")

        assertTrue(firstResult.get() is CommandResult.Success)
        assertTrue(secondResult.get() is CommandResult.Success)

        val firstResp = (firstResult.get() as CommandResult.Success).response
        val secondResp = (secondResult.get() as CommandResult.Success).response
        assertEquals(id1, firstResp.id)
        assertEquals(id2, secondResp.id)
    }

    // ============== Successful response ==============

    @Test
    fun successfulResponse_resolvesCallback() {
        val resultRef = AtomicReference<CommandResult?>()
        dispatcher.sendCommand("Browser.getVersion") { resultRef.set(it) }

        val id = extractId(transport.sentMessages.last())
        transport.deliverMessage("""{"id":$id,"result":{"browser":"Chrome"}}""")

        val result = resultRef.get()
        assertNotNull(result)
        assertTrue(result is CommandResult.Success)
        val response = (result as CommandResult.Success).response
        assertEquals("Chrome", (response.result as Map<*, *>)["browser"])
    }

    // ============== Protocol failure ==============

    @Test
    fun protocolError_resolvesWithFailure() {
        val resultRef = AtomicReference<CommandResult?>()
        dispatcher.sendCommand("Bad.method") { resultRef.set(it) }

        val id = extractId(transport.sentMessages.last())
        transport.deliverMessage("""{"id":$id,"error":{"code":-32601,"message":"Method not found"}}""")

        val result = resultRef.get()
        assertNotNull(result)
        assertTrue(result is CommandResult.Failure)
        val error = (result as CommandResult.Failure).error
        assertTrue(error is ProtocolException)
        assertTrue(error.message?.contains("Method not found") == true)
    }

    // ============== Timeout ==============

    @Test
    fun commandTimeout_failsWithTimeoutException() {
        val resultRef = AtomicReference<CommandResult?>()
        dispatcher.sendCommand("Slow.method", timeoutMs = 50) { resultRef.set(it) }

        // Wait for timeout
        Thread.sleep(150)

        val result = resultRef.get()
        assertNotNull(result)
        assertTrue(result is CommandResult.Failure)
        assertTrue((result as CommandResult.Failure).error is CommandTimeoutException)
        assertEquals(0, dispatcher.pendingCommandCount)
    }

    // ============== Connection failure cleanup ==============

    @Test
    fun connectionFailure_failsAllPendingCommands() {
        val results = (1..5).map { AtomicReference<CommandResult?>() }
        repeat(5) { i ->
            dispatcher.sendCommand("Method.$i") { results[i].set(it) }
        }

        assertEquals(5, dispatcher.pendingCommandCount)

        // Simulate connection failure
        dispatcher.onError(TransportException("Connection lost"))

        assertEquals(0, dispatcher.pendingCommandCount)

        results.forEach { ref ->
            val result = ref.get()
            assertTrue(result is CommandResult.Failure)
            // Transport errors propagate as-is
            assertTrue((result as CommandResult.Failure).error is TransportException)
        }
    }

    // ============== Connection closed cleanup ==============

    @Test
    fun connectionClosed_failsAllPendingCommands() {
        val resultRef = AtomicReference<CommandResult?>()
        dispatcher.sendCommand("Method.test") { resultRef.set(it) }

        dispatcher.onClosed("Normal closure")

        val result = resultRef.get()
        assertTrue(result is CommandResult.Failure)
        assertTrue((result as CommandResult.Failure).error is ConnectionException)
        assertEquals(0, dispatcher.pendingCommandCount)
    }

    // ============== Orphaned responses ==============

    @Test
    fun orphanedResponse_doesNotCrash() {
        transport.deliverMessage("""{"id":99999,"result":{}}""")
        // Should not throw
    }

    // ============== Event routing via dispatcher ==============

    @Test
    fun eventReceived_routedToRouter() {
        var eventReceived: Event? = null
        eventRouter.subscribe("Target.targetCreated") { event -> eventReceived = event }

        transport.deliverMessage("""{"method":"Target.targetCreated","params":{"targetInfo":{"targetId":"t1","type":"page"}}}""")

        assertNotNull(eventReceived)
        assertEquals("Target.targetCreated", eventReceived!!.method)
    }

    // ============== Send when not connected ==============

    @Test
    fun sendCommand_projectParamsAsJsonObject_notDoubleEncodedString() {
        // Regression: params must be serialized as a true JSON object, never a
        // JSON-string-in-a-string ("params":"{\"discover\":true}"). Real Chrome
        // rejects the latter with -32600 "Message may have object 'params' property".
        dispatcher.sendCommand("Target.setDiscoverTargets", mapOf("discover" to true)) { }

        val sent = transport.sentMessages.last()
        // The params must be an object, not a quoted string.
        assertTrue("params should be a JSON object: $sent", sent.contains("\"params\":{\"discover\":true}"))
        assertFalse("params must not be double-encoded as a string: $sent", sent.contains("\"params\":\"{"))
    }

    @Test
    fun sendCommand_whenDisconnected_failsImmediately() {
        val disconnectedTransport = FakeCdpTransport()
        val router = CdpEventRouter()
        val config = DispatcherConfig(defaultTimeoutMs = 5000)
        val dispatcher2 = CommandDispatcher(disconnectedTransport, router, config)

        // Don't connect
        val resultRef = AtomicReference<CommandResult?>()
        dispatcher2.sendCommand("Test.method") { resultRef.set(it) }

        val result = resultRef.get()
        assertNotNull(result)
        assertTrue(result is CommandResult.Failure)
    }

    private fun extractId(json: String): Long {
        val map = com.coppermux.cutools.core.model.Protocol.moshi.adapter(Map::class.java).fromJson(json)
        return (map?.get("id") as Number).toLong()
    }
}
