package com.coppermux.cutools.core.target

import com.coppermux.cutools.core.target.models.TargetActionResult
import com.coppermux.cutools.core.target.models.TargetCapabilityStatus
import com.coppermux.cutools.core.target.models.TargetError
import com.coppermux.cutools.core.target.models.TargetInfoModel
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit & protocol test suite for Phase 12 Target Domain Integration Engine.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → router → dispatcher → engine, proving
 * `Target.setDiscoverTargets/getTargets/attachToTarget/detachFromTarget`
 * serialization, genuine `Target.targetCreated` / `Target.targetDestroyed` /
 * `Target.targetInfoChanged` event routing into the real-time [targets] stream,
 * and strict error mapping. No-Fake-Capability is enforced: protocol failures,
 * missing sessions, unavailable methods, and connection loss map to explicit
 * UNSUPPORTED / UNAVAILABLE / FAILED / TARGET_DISCONNECTED outcomes, never
 * fabricated target state.
 */
class TargetEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: TargetEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = TargetEngine(dispatcher, router)
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

    private fun deliverEvent(method: String, paramsJson: String, eventSessionId: String? = "session-1") {
        val sessionPart = if (eventSessionId == null) "" else "\"sessionId\":\"$eventSessionId\","
        transport.deliverMessage("""{"method":"$method",$sessionPart"params":$paramsJson}""")
    }

    // ============ payload generation ============

    @Test
    fun setDiscoverTargets_true_serializesDiscoverAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        engine.setDiscoverTargets(true) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Target.setDiscoverTargets") }
        assertTrue(raw.contains("\"method\":\"Target.setDiscoverTargets\""))
        assertTrue(raw.contains("\"discover\":true"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun setDiscoverTargets_false_serializesDiscoverFalse() {
        bind()
        engine.setDiscoverTargets(false)
        val raw = transport.sentMessages.last { it.contains("Target.setDiscoverTargets") }
        assertTrue(raw.contains("\"discover\":false"))
    }

    @Test
    fun getTargets_serializesMethodAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<List<TargetInfoModel>>>()
        engine.getTargets { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Target.getTargets") }
        assertTrue(raw.contains("\"method\":\"Target.getTargets\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun attachToTarget_serializesTargetIdFlattenAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<String>>()
        engine.attachToTarget("target-7", true) { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Target.attachToTarget") }
        assertTrue(raw.contains("\"method\":\"Target.attachToTarget\""))
        assertTrue(raw.contains("\"targetId\":\"target-7\""))
        assertTrue(raw.contains("\"flatten\":true"))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun attachToTarget_flattenFalse_serializesFlattenFalse() {
        bind()
        engine.attachToTarget("target-2", false) { }
        val raw = transport.sentMessages.last { it.contains("Target.attachToTarget") }
        assertTrue(raw.contains("\"flatten\":false"))
    }

    @Test
    fun detachFromTarget_serializesSessionId() {
        bind()
        engine.detachFromTarget("session-9")
        val raw = transport.sentMessages.last { it.contains("Target.detachFromTarget") }
        assertTrue(raw.contains("\"method\":\"Target.detachFromTarget\""))
        assertTrue(raw.contains("\"sessionId\":\"session-9\""))
    }

    // ============ event parsing & StateFlow ============

    @Test
    fun targetCreated_populatesTargetsStream() {
        bind()
        deliverEvent(
            "Target.targetCreated",
            """{"targetInfo":{"targetId":"t1","type":"page","title":"Title","url":"https://a.example/","attached":false,"openerId":"p1","browserContextId":"bc1"}}"""
        )
        assertTrue(waitFor { engine.targets.value.size == 1 })
        val info = engine.targets.value[0]
        assertEquals("t1", info.targetId)
        assertEquals("page", info.type)
        assertEquals("Title", info.title)
        assertEquals("https://a.example/", info.url)
        assertTrue(!info.attached)
        assertEquals("p1", info.openerId)
        assertEquals("bc1", info.browserContextId)
    }

    @Test
    fun targetCreated_withoutSessionId_isAccepted() {
        bind()
        deliverEvent("Target.targetCreated", """{"targetInfo":{"targetId":"t1","type":"browser"}}""", eventSessionId = null)
        assertTrue(waitFor { engine.targets.value.size == 1 })
    }

    @Test
    fun targetDestroyed_removesFromStream() {
        bind()
        deliverEvent("Target.targetCreated", """{"targetInfo":{"targetId":"t1","type":"page"}}""")
        assertTrue(waitFor { engine.targets.value.size == 1 })
        deliverEvent("Target.targetDestroyed", """{"targetId":"t1"}""")
        assertTrue(waitFor { engine.targets.value.isEmpty() })
    }

    @Test
    fun targetInfoChanged_updatesExistingEntry() {
        bind()
        deliverEvent("Target.targetCreated", """{"targetInfo":{"targetId":"t1","type":"page","title":"Before"}}""")
        assertTrue(waitFor { engine.targets.value.size == 1 })
        deliverEvent("Target.targetInfoChanged", """{"targetInfo":{"targetId":"t1","type":"page","title":"After","attached":true}}""")
        assertTrue(waitFor { engine.targets.value[0].title == "After" })
        assertEquals(1, engine.targets.value.size)
        assertTrue(engine.targets.value[0].attached)
    }

    @Test
    fun events_keepNewestEntryPerTarget() {
        bind()
        deliverEvent("Target.targetCreated", """{"targetInfo":{"targetId":"t1","type":"page","title":"V1"}}""")
        deliverEvent("Target.targetInfoChanged", """{"targetInfo":{"targetId":"t1","type":"page","title":"V2"}}""")
        deliverEvent("Target.targetInfoChanged", """{"targetInfo":{"targetId":"t1","type":"page","title":"V3"}}""")
        assertTrue(waitFor { engine.targets.value.size == 1 && engine.targets.value[0].title == "V3" })
    }

    @Test
    fun events_forOtherSession_areIgnored() {
        bind()
        deliverEvent("Target.targetCreated", """{"targetInfo":{"targetId":"t1","type":"page"}}""", eventSessionId = "other-session")
        Thread.sleep(200)
        assertTrue(engine.targets.value.isEmpty())
    }

    @Test
    fun events_malformedParams_areTolerated() {
        bind()
        transport.deliverMessage("""{"method":"Target.targetCreated","sessionId":"session-1","params":"not-a-map"}""")
        deliverEvent("Target.targetCreated", """{"targetInfo":"not-a-map"}""")
        deliverEvent("Target.targetDestroyed", """{"targetId":123}""")
        Thread.sleep(200)
        assertTrue(engine.targets.value.isEmpty())
    }

    // ============ getTargets response parsing ============

    @Test
    fun getTargets_success_parsesTargetInfosIntoStream() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<List<TargetInfoModel>>>()
        engine.getTargets { results.add(it) }
        val id = parseIdFor("Target.getTargets")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"targetInfos":[{"targetId":"t1","type":"page","title":"A","url":"https://a.example/"},{"targetId":"t2","type":"browser"}]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is TargetActionResult.Success)
        val parsed = (results[0] as TargetActionResult.Success).data
        assertEquals(2, parsed.size)
        assertEquals("A", parsed[0].title)
        assertEquals("browser", parsed[1].type)
        assertEquals(2, engine.targets.value.size)
        assertEquals(TargetCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun getTargets_missingTargetInfos_yieldsEmpty() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<List<TargetInfoModel>>>()
        engine.getTargets { results.add(it) }
        val id = parseIdFor("Target.getTargets")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as TargetActionResult.Success).data.isEmpty())
        assertTrue(engine.targets.value.isEmpty())
    }

    @Test
    fun getTargets_nonObjectResult_mapsToFailure() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<List<TargetInfoModel>>>()
        engine.getTargets { results.add(it) }
        val id = parseIdFor("Target.getTargets")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":[]}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is TargetActionResult.Failure)
        assertTrue((results[0] as TargetActionResult.Failure).error is TargetError.ExecutionFailed)
        assertEquals(TargetCapabilityStatus.FAILED, engine.capability)
    }

    // ============ mutation / domain success ============

    @Test
    fun attachToTarget_success_returnsSessionId() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<String>>()
        engine.attachToTarget("target-2") { results.add(it) }
        val id = parseIdFor("Target.attachToTarget")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"sessionId":"attached-sess"}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is TargetActionResult.Success)
        assertEquals("attached-sess", (results[0] as TargetActionResult.Success).data)
        assertEquals(TargetCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun attachToTarget_missingSessionId_mapsToFailure() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<String>>()
        engine.attachToTarget("target-2") { results.add(it) }
        val id = parseIdFor("Target.attachToTarget")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as TargetActionResult.Failure).error is TargetError.ExecutionFailed)
        assertEquals(TargetCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun setDiscoverTargets_success_setsCapability() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        engine.setDiscoverTargets(true) { results.add(it) }
        val id = parseIdFor("Target.setDiscoverTargets")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is TargetActionResult.Success)
        assertEquals(TargetCapabilityStatus.SUPPORTED, engine.capability)
    }

    @Test
    fun detachFromTarget_success_returnsSuccess() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        engine.detachFromTarget("session-9") { results.add(it) }
        val id = parseIdFor("Target.detachFromTarget")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is TargetActionResult.Success)
    }

    // ============ error mapping ============

    @Test
    fun commands_withoutBoundSession_areUnavailable() {
        val unavail = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        engine.setDiscoverTargets(true) { unavail.add(it) }
        assertTrue((unavail[0] as TargetActionResult.Failure).error is TargetError.Unavailable)
        assertEquals(TargetCapabilityStatus.UNAVAILABLE, engine.capability)

        val detached = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        engine.detachFromTarget("s") { detached.add(it) }
        assertTrue((detached[0] as TargetActionResult.Failure).error is TargetError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getTargets_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<TargetActionResult<List<TargetInfoModel>>>()
        engine.getTargets { results.add(it) }
        assertTrue((results[0] as TargetActionResult.Failure).error is TargetError.Unavailable)
    }

    @Test
    fun attachToTarget_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<TargetActionResult<String>>()
        engine.attachToTarget("target-2") { results.add(it) }
        assertTrue((results[0] as TargetActionResult.Failure).error is TargetError.Unavailable)
    }

    @Test
    fun methodUnavailable_mapsToUnsupported() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        engine.setDiscoverTargets(true) { results.add(it) }
        val id = parseIdFor("Target.setDiscoverTargets")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as TargetActionResult.Failure).error is TargetError.Unsupported)
        assertEquals(TargetCapabilityStatus.UNSUPPORTED, engine.capability)
    }

    @Test
    fun protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<List<TargetInfoModel>>>()
        engine.getTargets { results.add(it) }
        val id = parseIdFor("Target.getTargets")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"getTargets broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as TargetActionResult.Failure).error is TargetError.ExecutionFailed)
        assertEquals(TargetCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun afterConnectionLost_mapsToTargetDisconnected() {
        bind()
        transport.disconnect()
        val results = CopyOnWriteArrayList<TargetActionResult<Unit>>()
        engine.setDiscoverTargets(true) { results.add(it) }
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as TargetActionResult.Failure).error is TargetError.TargetDisconnected)
    }

    // ============ lifecycle / target switch & detach ============

    @Test
    fun detach_clearsStateAndUnsubscribes() {
        bind()
        deliverEvent("Target.targetCreated", """{"targetInfo":{"targetId":"t1","type":"page"}}""")
        assertTrue(waitFor { engine.targets.value.size == 1 })
        for (method in listOf("Target.targetCreated", "Target.targetDestroyed", "Target.targetInfoChanged")) {
            assertEquals("one listener per method", 1, router.subscriptionCount(method))
        }

        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
        assertTrue(engine.targets.value.isEmpty())
        for (method in listOf("Target.targetCreated", "Target.targetDestroyed", "Target.targetInfoChanged")) {
            assertEquals("no event-listener leak for $method", 0, router.subscriptionCount(method))
        }
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        deliverEvent("Target.targetCreated", """{"targetInfo":{"targetId":"t1","type":"page"}}""")
        assertTrue(waitFor { engine.targets.value.size == 1 })

        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
        assertTrue(engine.targets.value.isEmpty())
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        deliverEvent("Target.targetCreated", """{"targetInfo":{"targetId":"t1","type":"page"}}""")
        assertTrue(waitFor { engine.targets.value.size == 1 })
        engine.onConnectionLost()
        assertNull(engine.session)
        assertTrue(engine.targets.value.isEmpty())
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<TargetActionResult<String>>()
        engine.attachToTarget("target-2") { results.add(it) }
        val id = parseIdFor("Target.attachToTarget")

        engine.detach()
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"sessionId":"s"}}""")
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
    }

    @Test
    fun lateEventAfterDetach_isIgnored() {
        bind()
        engine.detach()
        deliverEvent("Target.targetCreated", """{"targetInfo":{"targetId":"t1","type":"page"}}""")
        Thread.sleep(200)
        assertTrue(engine.targets.value.isEmpty())
    }

    @Test
    fun rebind_doesNotAccumulateSubscriptions() {
        bind()
        val other = SessionInfo("session-3", "target-3", "ws://test")
        engine.bindSession(other)
        engine.bindSession(SessionInfo("session-4", "target-4", "ws://test"))
        assertEquals("rebinding must not leak extra listeners", 1, router.subscriptionCount("Target.targetCreated"))
    }

    @Test
    fun shutdown_releasesSubscriptions() {
        bind()
        assertEquals(1, router.subscriptionCount("Target.targetCreated"))
        engine.shutdown()
        assertEquals(0, router.subscriptionCount("Target.targetCreated"))
        assertEquals(0, router.subscriptionCount("Target.targetDestroyed"))
        assertEquals(0, router.subscriptionCount("Target.targetInfoChanged"))
    }

    // ============ model-level mapping ============

    @Test
    fun targetActionResult_and_error_factories_produceExpectedValues() {
        val success = TargetActionResult.Success(Unit)
        assertTrue(success.data === Unit)

        val unsupported: TargetError = TargetError.Unsupported
        val unavailable: TargetError = TargetError.Unavailable
        val disconnected: TargetError = TargetError.TargetDisconnected
        val failed: TargetError = TargetError.ExecutionFailed("boom")
        assertEquals("boom", (failed as TargetError.ExecutionFailed).message)

        val failure = TargetActionResult.Failure(unsupported)
        assertTrue(failure.error === unsupported)
        assertTrue(unavailable is TargetError.Unavailable)
        assertTrue(disconnected is TargetError.TargetDisconnected)
    }

    @Test
    fun targetParser_parsesSafely() {
        val model = TargetParser.parseTargetInfo(mapOf<String, Any?>("targetId" to "t1", "type" to "page", "title" to "T"))
        assertNotNull(model)
        assertEquals("t1", model!!.targetId)
        assertEquals("T", model!!.title)
        assertTrue(!model!!.attached)
        assertNull(model!!.openerId)
        assertNull(TargetParser.parseTargetInfo(mapOf<String, Any?>("targetId" to "t1")))
        assertNull(TargetParser.parseTargetInfo("not-a-map"))
        assertNull(TargetParser.parseTargetInfo(null))

        assertEquals(0, TargetParser.parseGetTargets("not-a-map").size)
        assertEquals("gone", TargetParser.parseDestroyedTargetId(mapOf<String, Any?>("targetId" to "gone")))
        assertNull(TargetParser.parseDestroyedTargetId(mapOf<String, Any?>("x" to "y")))
    }
}