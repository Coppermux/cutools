package com.coppermux.cutools.core.accessibility

import com.coppermux.cutools.core.accessibility.models.AXPropertyType
import com.coppermux.cutools.core.accessibility.models.AXTreeModel
import com.coppermux.cutools.core.accessibility.models.AccessibilityActionResult
import com.coppermux.cutools.core.accessibility.models.AccessibilityCapabilityStatus
import com.coppermux.cutools.core.accessibility.models.AccessibilityError
import com.coppermux.cutools.core.target.SessionInfo
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
 * Unit & protocol test suite for Phase 9A Accessibility Domain Engine & Tree
 * Model.
 *
 * Raw CDP JSON messages are delivered via the fake transport and flow through
 * the classifier → router → dispatcher → engine, proving
 * `Accessibility.enable/disable/getFullAXTree/getPartialAXTree`
 * serialization, genuine `nodes`-array tree parsing, error mapping, and
 * lifecycle safety in the same pipeline the app uses against Chrome.
 * No-Fake-Capability is enforced: protocol failures, missing sessions,
 * unavailable methods, and connection loss map to explicit UNSUPPORTED /
 * UNAVAILABLE / FAILED / TARGET_DISCONNECTED outcomes, never fabricated AX
 * tree or role state.
 */
class AccessibilityEngineTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var engine: AccessibilityEngine

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        dispatcher = CommandDispatcher(transport, null, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        engine = AccessibilityEngine(dispatcher)
    }

    private fun bind() {
        engine.bindSession(session)
        val id = parseIdFor("Accessibility.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { engine.enabled })
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
    fun bind_sendsEnableOnSession() {
        engine.bindSession(session)
        val raw = transport.sentMessages.last { it.contains("Accessibility.enable") }
        assertTrue(raw.contains("\"method\":\"Accessibility.enable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun disable_serializesDisableAndSessionId() {
        bind()
        val results = CopyOnWriteArrayList<AccessibilityActionResult<Unit>>()
        engine.disable { results.add(it) }
        val raw = transport.sentMessages.last { it.contains("Accessibility.disable") }
        assertTrue(raw.contains("\"method\":\"Accessibility.disable\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
    }

    @Test
    fun getFullAXTree_withParams_serializesDepthMaxDepthFrameId() {
        bind()
        engine.getFullAXTree(depth = 2, maxDepth = 5, frameId = "frame-1") { }
        val raw = transport.sentMessages.last { it.contains("Accessibility.getFullAXTree") }
        assertTrue(raw.contains("\"method\":\"Accessibility.getFullAXTree\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"depth\":2"))
        assertTrue(raw.contains("\"max_depth\":5"))
        assertTrue(raw.contains("\"frameId\":\"frame-1\""))
    }

    @Test
    fun getPartialAXTree_withParams_serializesAllParams() {
        bind()
        engine.getPartialAXTree(nodeId = 10, backendNodeId = 20, objectId = "obj-1", fetchRelatives = true) { }
        val raw = transport.sentMessages.last { it.contains("Accessibility.getPartialAXTree") }
        assertTrue(raw.contains("\"method\":\"Accessibility.getPartialAXTree\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(raw.contains("\"nodeId\":10"))
        assertTrue(raw.contains("\"backendNodeId\":20"))
        assertTrue(raw.contains("\"objectId\":\"obj-1\""))
        assertTrue(raw.contains("\"fetchRelatives\":true"))
    }

    @Test
    fun getPartialAXTree_withoutParams_serializesMethodAndSessionOnly() {
        bind()
        engine.getPartialAXTree { }
        val raw = transport.sentMessages.last { it.contains("Accessibility.getPartialAXTree") }
        assertTrue(raw.contains("\"method\":\"Accessibility.getPartialAXTree\""))
        assertTrue(raw.contains("\"sessionId\":\"session-1\""))
        assertTrue(!raw.contains("\"nodeId\""))
        assertTrue(!raw.contains("\"backendNodeId\""))
    }

    // ============ AX tree parsing ============

    @Test
    fun getFullAXTree_success_parsesFullHierarchyAndRoot() {
        bind()
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        engine.getFullAXTree { results.add(it) }
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"nodes":[
                {"nodeId":"0","ignored":false,"role":{"type":"role","value":"RootWebArea"},"name":{"type":"name","value":"Page"},"properties":[{"name":"hidden","value":{"type":"boolean","value":false}}],"childIds":["1","2"]},
                {"nodeId":"1","ignored":false,"role":{"type":"role","value":"button"},"name":{"type":"name","value":"Submit"},"properties":[{"name":"valuenow","value":{"type":"number","value":0}}],"backendDOMNodeId":55},
                {"nodeId":"2","ignored":true,"ignoredReasons":[{"name":"hidden","value":{"type":"boolean","value":true}}]}
            ]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AccessibilityActionResult.Success<*>)
        val tree = (results[0] as AccessibilityActionResult.Success<AXTreeModel>).data
        assertEquals(3, tree.nodes.size)
        assertEquals("0", tree.rootNodeId)
        assertEquals("Page", tree.nodes[0].name!!.value)
        assertEquals("button", tree.nodes[1].role!!.value)
        assertEquals(55, tree.nodes[1].backendDOMNodeId)
        assertTrue(tree.nodes[1].properties[0].name == "valuenow")
        assertTrue(tree.nodes[2].ignored)
        assertEquals(1, tree.nodes[2].ignoredReasons.size)
        assertEquals("hidden", tree.nodes[2].ignoredReasons[0].name)
    }

    @Test
    fun getPartialAXTree_success_parsesSubtreeNodes() {
        bind()
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        engine.getPartialAXTree(objectId = "obj-9", fetchRelatives = true) { results.add(it) }
        val id = parseIdFor("Accessibility.getPartialAXTree")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"nodes":[
                {"nodeId":"a","ignored":false,"name":{"type":"text","value":"Heading"},"childIds":["b"]},
                {"nodeId":"b","ignored":false,"name":{"type":"text","value":"Subheading"}}
            ]}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AccessibilityActionResult.Success<*>)
        val tree = (results[0] as AccessibilityActionResult.Success<AXTreeModel>).data
        assertEquals(2, tree.nodes.size)
        assertEquals("a", tree.rootNodeId)
        assertEquals("Subheading", tree.nodes[1].name!!.value)
    }

    @Test
    fun getFullAXTree_nonObjectResult_parsesEmptyTree() {
        bind()
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        engine.getFullAXTree { results.add(it) }
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":"not-a-map"}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AccessibilityActionResult.Success<*>)
        val tree = (results[0] as AccessibilityActionResult.Success<AXTreeModel>).data
        assertTrue(tree.nodes.isEmpty())
        assertNull(tree.rootNodeId)
    }

    @Test
    fun parser_tree_missingNodes_defaultsEmptyTree() {
        val tree = AccessibilityParser.parseTree(mapOf<String, Any?>("nodes" to listOf<Any?>()))
        assertNotNull(tree)
        assertTrue(tree!!.nodes.isEmpty())
        assertNull(tree!!.rootNodeId)
        assertNull(AccessibilityParser.parseTree("not-a-map"))
        assertNull(AccessibilityParser.parseTree(null))
    }

    @Test
    fun parser_node_missingMandatoryId_isSkipped() {
        val tree = AccessibilityParser.parseTree(
            mapOf<String, Any?>(
                "nodes" to listOf(
                    mapOf<String, Any?>("ignored" to true),
                    mapOf<String, Any?>("nodeId" to "n1", "ignored" to false),
                )
            )
        )
        assertNotNull(tree)
        assertEquals(1, tree!!.nodes.size)
        assertEquals("n1", tree!!.nodes[0].nodeId)
    }

    @Test
    fun parser_node_missingOptionalFields_safeDefaults() {
        val node = AccessibilityParser.parseNode(mapOf<String, Any?>("nodeId" to "x", "ignored" to false))
        assertNotNull(node)
        assertNull(node!!.role)
        assertNull(node!!.name)
        assertNull(node!!.description)
        assertNull(node!!.value)
        assertNull(node!!.backendDOMNodeId)
        assertTrue(node!!.properties.isEmpty())
        assertTrue(node!!.ignoredReasons.isEmpty())
        assertTrue(node!!.childIds.isEmpty())
        assertNull(AccessibilityParser.parseNode("not-a-map"))
        assertNull(AccessibilityParser.parseNode(null))
    }

    @Test
    fun parser_valueAndSources_parsesPropertiesAndValueSources() {
        val property = AccessibilityParser.parseProperty(
            mapOf<String, Any?>(
                "name" to "valuenow",
                "value" to mapOf(
                    "type" to "number",
                    "value" to 0,
                    "sources" to listOf(
                        mapOf("type" to "property", "value" to "0", "attribute" to "aria-valuenow", "attributeValue" to "0", "superseded" to true, "invalid" to false)
                    )
                )
            )
        )
        assertNotNull(property)
        assertEquals("valuenow", property!!.name)
        assertEquals("0", property!!.value.value)
        assertEquals(1, property!!.value.sources.size)
        val source = property!!.value.sources[0]
        assertEquals("property", source.type)
        assertEquals("0", source.value)
        assertEquals("aria-valuenow", source.attribute)
        assertTrue(source.superseded)
        assertTrue(!source.invalid)
        assertNull(AccessibilityParser.parseProperty(mapOf<String, Any?>("value" to mapOf<String, Any?>())))
        assertNull(AccessibilityParser.parseProperty("not-a-map"))
    }

    // ============ error mapping ============

    @Test
    fun enable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<AccessibilityActionResult<Unit>>()
        engine.enable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is AccessibilityActionResult.Failure)
        assertTrue((results[0] as AccessibilityActionResult.Failure).error === AccessibilityError.Unavailable)
        assertEquals(AccessibilityCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun disable_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<AccessibilityActionResult<Unit>>()
        engine.disable { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as AccessibilityActionResult.Failure).error === AccessibilityError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getFullAXTree_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        engine.getFullAXTree { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as AccessibilityActionResult.Failure).error === AccessibilityError.Unavailable)
        assertEquals(AccessibilityCapabilityStatus.UNAVAILABLE, engine.capability)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun getPartialAXTree_withoutBoundSession_isUnavailable() {
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        engine.getPartialAXTree { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as AccessibilityActionResult.Failure).error === AccessibilityError.Unavailable)
        assertEquals(0, transport.sentMessages.size)
    }

    @Test
    fun enable_methodUnavailable_mapsToUnsupported() {
        engine.bindSession(session)
        val id = parseIdFor("Accessibility.enable")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(waitFor { engine.capability == AccessibilityCapabilityStatus.UNSUPPORTED })
        assertEquals(AccessibilityCapabilityStatus.UNSUPPORTED, engine.capability)
        assertTrue(!engine.enabled)
    }

    @Test
    fun getFullAXTree_protocolFailure_mapsToExecutionFailed() {
        bind()
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        engine.getFullAXTree { results.add(it) }
        val id = parseIdFor("Accessibility.getFullAXTree")
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"tree fetch broke"}}"""
        )
        assertTrue(waitFor { results.size == 1 })
        val failure = results[0] as AccessibilityActionResult.Failure
        assertTrue(failure.error is AccessibilityError.ExecutionFailed)
        assertTrue((failure.error as AccessibilityError.ExecutionFailed).message.contains("tree fetch broke"))
        assertEquals(AccessibilityCapabilityStatus.FAILED, engine.capability)
    }

    @Test
    fun getPartialAXTree_afterConnectionLost_mapsToTargetDisconnected() {
        bind()
        transport.disconnect()
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        engine.getPartialAXTree { results.add(it) }
        assertTrue(waitFor { results.size == 1 })
        assertTrue((results[0] as AccessibilityActionResult.Failure).error === AccessibilityError.TargetDisconnected)
    }

    // ============ domain success ============

    @Test
    fun enable_success_setsEnabledAndCapability() {
        engine.bindSession(session)
        val results = CopyOnWriteArrayList<AccessibilityActionResult<Unit>>()
        engine.enable { results.add(it) }
        val id = parseIdFor("Accessibility.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AccessibilityActionResult.Success<*>)
        assertEquals(AccessibilityCapabilityStatus.SUPPORTED, engine.capability)
        assertTrue(engine.enabled)
    }

    @Test
    fun disable_success_disablesDomain() {
        bind()
        val results = CopyOnWriteArrayList<AccessibilityActionResult<Unit>>()
        engine.disable { results.add(it) }
        val id = parseIdFor("Accessibility.disable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        assertTrue(waitFor { results.size == 1 })
        assertTrue(results[0] is AccessibilityActionResult.Success<*>)
        assertTrue(waitFor { !engine.enabled })
    }

    // ============ lifecycle / detach safety ============

    @Test
    fun detach_clearsStateAndCapability() {
        bind()
        engine.detach()
        assertNull(engine.session)
        assertNull(engine.capability)
        assertTrue(!engine.enabled)
    }

    @Test
    fun bindSession_resetsPreviousTargetState() {
        bind()
        val other = SessionInfo("session-2", "target-2", "ws://test")
        engine.bindSession(other)
        assertEquals(other, engine.session)
        assertNull(engine.capability)
        assertTrue(!engine.enabled)
    }

    @Test
    fun onConnectionLost_detachesAndClearsState() {
        bind()
        engine.onConnectionLost()
        assertNull(engine.session)
        assertNull(engine.capability)
        assertTrue(!engine.enabled)
    }

    @Test
    fun staleResponseAfterDetach_isIgnored() {
        bind()
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        engine.getFullAXTree { results.add(it) }
        val id = parseIdFor("Accessibility.getFullAXTree")
        engine.detach()
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"nodes":[{"nodeId":"n","ignored":false}]}}"""
        )
        Thread.sleep(100)
        assertTrue(results.isEmpty())
        assertNull(engine.session)
    }

    @Test
    fun getFullAXTree_afterDetach_isUnavailable() {
        bind()
        engine.detach()
        val results = CopyOnWriteArrayList<AccessibilityActionResult<AXTreeModel>>()
        engine.getFullAXTree { results.add(it) }
        assertEquals(1, results.size)
        assertTrue((results[0] as AccessibilityActionResult.Failure).error === AccessibilityError.Unavailable)
    }

    // ============ model-level mapping ============

    @Test
    fun axPropertyType_fromString_mapsAllValues() {
        assertEquals(AXPropertyType.BUSY, AXPropertyType.fromWire("busy"))
        assertEquals(AXPropertyType.DISABLED, AXPropertyType.fromWire("disabled"))
        assertEquals(AXPropertyType.EXPANDED, AXPropertyType.fromWire("expanded"))
        assertEquals(AXPropertyType.FOCUSABLE, AXPropertyType.fromWire("focusable"))
        assertEquals(AXPropertyType.FOCUSED, AXPropertyType.fromWire("focused"))
        assertEquals(AXPropertyType.HIDDEN, AXPropertyType.fromWire("hidden"))
        assertEquals(AXPropertyType.HIDDEN_ROOT, AXPropertyType.fromWire("hiddenRoot"))
        assertEquals(AXPropertyType.INVALID, AXPropertyType.fromWire("invalid"))
        assertEquals(AXPropertyType.READONLY, AXPropertyType.fromWire("readonly"))
        assertEquals(AXPropertyType.REQUIRED, AXPropertyType.fromWire("required"))
        assertEquals(AXPropertyType.SELECTED, AXPropertyType.fromWire("selected"))
        assertEquals(AXPropertyType.CHECKED, AXPropertyType.fromWire("checked"))
        assertEquals(AXPropertyType.PRESSED, AXPropertyType.fromWire("pressed"))
        assertEquals(AXPropertyType.LEVEL, AXPropertyType.fromWire("level"))
        assertEquals(AXPropertyType.VALUEMIN, AXPropertyType.fromWire("valuemin"))
        assertEquals(AXPropertyType.VALUEMAX, AXPropertyType.fromWire("valuemax"))
        assertEquals(AXPropertyType.VALUENOW, AXPropertyType.fromWire("valuenow"))
        assertEquals(AXPropertyType.VALUETEXT, AXPropertyType.fromWire("valuetext"))
        assertNull(AXPropertyType.fromWire("bogus"))
        assertNull(AXPropertyType.fromWire(null))
    }

    @Test
    fun accessibilityActionResult_and_error_factories_produceExpectedValues() {
        val ok = AccessibilityActionResult.Success(42)
        assertTrue((ok as AccessibilityActionResult.Success<Int>).data == 42)

        val failure = AccessibilityActionResult.Failure(AccessibilityError.Unsupported)
        assertTrue(failure.error === AccessibilityError.Unsupported)

        val exec = AccessibilityError.ExecutionFailed("boom")
        assertEquals("boom", exec.message)
        assertTrue(AccessibilityError.Unavailable === AccessibilityError.Unavailable)
        assertTrue(AccessibilityError.TargetDisconnected === AccessibilityError.TargetDisconnected)
    }

    @Test
    fun parser_missingFields_parseSafely() {
        val value = AccessibilityParser.parseValue(mapOf<String, Any?>("value" to "hello"))
        assertNotNull(value)
        assertEquals("", value!!.type)
        assertEquals("hello", value!!.value)
        assertTrue(value!!.sources.isEmpty())
        assertNull(AccessibilityParser.parseValue("not-a-map"))
        assertNull(AccessibilityParser.parseValue(null))

        val source = AccessibilityParser.parseSource(mapOf<String, Any?>("type" to "property"))
        assertNotNull(source)
        assertEquals("property", source!!.type)
        assertNull(source!!.attribute)
        assertTrue(!source!!.superseded)
        assertNull(AccessibilityParser.parseSource("not-a-map"))

        val list = AccessibilityParser.parseNodeList(listOf<Any?>("not-a-map", 5))
        assertTrue(list.isEmpty())
    }
}