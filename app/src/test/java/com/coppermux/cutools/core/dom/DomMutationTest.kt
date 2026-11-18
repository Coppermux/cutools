package com.coppermux.cutools.core.dom

import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic tests for Phase 2C — DOM Live Editing.
 *
 * These drive the real CommandDispatcher + EventRouter through a fake
 * in-memory transport; they never touch Chrome. They verify that mutations use
 * the correct CDP commands, that local state is reconciled only on success, and
 * that failures / stale / target-change / connection-loss scenarios never
 * corrupt local state.
 */
class DomMutationTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var inspector: DomInspector

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        inspector = DomInspector(dispatcher, router)
    }

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    private fun bindRichLoaded() {
        inspector.bindSession(session)
        val enableId = parseIdFor("DOM.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitForGetDocument()
        val getDocId = parseIdFor("DOM.getDocument")
        transport.deliverMessage(
            """{"id":$getDocId,"sessionId":"session-1","result":{"root":{
               "nodeId":1,"nodeType":9,"nodeName":"#document","childNodeCount":1,
               "children":[{"nodeId":2,"nodeType":1,"nodeName":"HTML","attributes":["lang","en"],"childNodeCount":1,
                 "children":[{"nodeId":3,"nodeType":1,"nodeName":"BODY","attributes":["id","main-body","class","content container wrap"],"childNodeCount":2,
                   "children":[
                     {"nodeId":4,"nodeType":1,"nodeName":"H1","attributes":["id","main","class","hero"],"childNodeCount":1,
                       "children":[{"nodeId":5,"nodeType":3,"nodeName":"#text","nodeValue":"Hello World","childNodeCount":0}]},
                     {"nodeId":6,"nodeType":1,"nodeName":"DIV","attributes":["id","box"],"childNodeCount":2}
                   ]}]}]}}}"""
        )
        awaitState(DomState.READY)
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.first { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitForGetDocument() {
        val deadline = System.currentTimeMillis() + 3000
        while (!transport.sentMessages.any { it.contains("DOM.getDocument") } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(transport.sentMessages.any { it.contains("DOM.getDocument") })
    }

    private fun awaitState(expected: DomState) {
        val deadline = System.currentTimeMillis() + 3000
        while (inspector.state() != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(expected, inspector.state())
    }

    private fun awaitMutation(
        start: (completion: (DomOperationResult<Unit>) -> Unit) -> Unit,
        respond: (() -> Unit)? = null,
    ): DomOperationResult<Unit> {
        val result = AtomicReference<DomOperationResult<Unit>>()
        val latch = CountDownLatch(1)
        start { r -> result.set(r); latch.countDown() }
        respond?.invoke()
        assertTrue("mutation did not complete", latch.await(3, TimeUnit.SECONDS))
        return result.get()
    }

    /** Delivers a success response for the last command with the given method. */
    private fun deliverSuccess(method: String) {
        val id = parseIdFor(method)
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
    }

    // ============ Attribute mutation ============

    @Test
    fun setAttributeValue_updatesExistingAttribute() {
        bindRichLoaded()
        val r = awaitMutation({ cb -> inspector.setAttributeValue(4, "id", "new-main", cb) }) { deliverSuccess("DOM.setAttributeValue") }
        assertTrue(r is DomOperationResult.Success)
        // Correct CDP command + params.
        val sent = transport.sentMessages.first { it.contains("DOM.setAttributeValue") }
        assertTrue(sent.contains("\"nodeId\":4"))
        assertTrue(sent.contains("\"name\":\"id\""))
        assertTrue(sent.contains("\"value\":\"new-main\""))
        // Local state reconciled.
        assertEquals("new-main", inspector.tree[4]?.attribute("id"))
        assertEquals("hero", inspector.tree[4]?.attribute("class"))
        assertEquals(DomMutationPhase.SUCCESS, inspector.mutationState.phase)
    }

    @Test
    fun setAttributeValue_protocolFailure_leavesStateUnchanged() {
        bindRichLoaded()
        val sentId = parseIdFor("DOM.enable")
        transport.resetSentMessages()
        inspector.setAttributeValue(4, "id", "changed", { })
        val id = parseIdFor("DOM.setAttributeValue")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"Cannot set attr"}}""")
        waitForMutationSettle()
        // Local state unchanged on failure.
        assertEquals("main", inspector.tree[4]?.attribute("id"))
        assertEquals(DomMutationPhase.FAILURE, inspector.mutationState.phase)
        assertNotNull(inspector.mutationState.error)
    }

    @Test
    fun addAttribute_preservesExistingAttributes() {
        bindRichLoaded()
        val r = awaitMutation({ cb -> inspector.addAttribute(4, "data-x", "42", cb) }) { deliverSuccess("DOM.setAttributesAsText") }
        assertTrue(r is DomOperationResult.Success)
        val sent = transport.sentMessages.first { it.contains("DOM.setAttributesAsText") }
        // The serialized text must contain existing attrs + the new one (JSON-escaped quotes).
        assertTrue(sent.contains("id=\\\"main\\\""))
        assertTrue(sent.contains("class=\\\"hero\\\""))
        assertTrue(sent.contains("data-x=\\\"42\\\""))
        // Local state.
        assertEquals("42", inspector.tree[4]?.attribute("data-x"))
        assertEquals("main", inspector.tree[4]?.attribute("id"))
        assertEquals("hero", inspector.tree[4]?.attribute("class"))
    }

    @Test
    fun addAttribute_emptyValue_supported() {
        bindRichLoaded()
        val r = awaitMutation({ cb -> inspector.addAttribute(4, "hidden", "", cb) }) { deliverSuccess("DOM.setAttributesAsText") }
        assertTrue(r is DomOperationResult.Success)
        val sent = transport.sentMessages.first { it.contains("DOM.setAttributesAsText") }
        assertTrue(sent.contains("hidden=\\\"\\\""))
        assertEquals("", inspector.tree[4]?.attribute("hidden"))
    }

    @Test
    fun removeAttribute_removesOnlyRequested() {
        bindRichLoaded()
        val r = awaitMutation({ cb -> inspector.removeAttribute(4, "class", cb) }) { deliverSuccess("DOM.removeAttribute") }
        assertTrue(r is DomOperationResult.Success)
        assertTrue(transport.sentMessages.any { it.contains("DOM.removeAttribute") && it.contains("\"name\":\"class\"") })
        assertNull(inspector.tree[4]?.attribute("class"))
        assertEquals("main", inspector.tree[4]?.attribute("id"))
    }

    @Test
    fun removeAttribute_protocolFailure_keepsAttribute() {
        bindRichLoaded()
        inspector.removeAttribute(4, "class", { })
        val id = parseIdFor("DOM.removeAttribute")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"nope"}}""")
        waitForMutationSettle()
        assertEquals("hero", inspector.tree[4]?.attribute("class"))
        assertEquals(DomMutationPhase.FAILURE, inspector.mutationState.phase)
    }

    @Test
    fun setAttributeValue_unknownNode_failsWithoutSending() {
        bindRichLoaded()
        transport.resetSentMessages()
        val r = awaitMutation({ cb -> inspector.setAttributeValue(999, "id", "x", cb) })
        assertTrue(r is DomOperationResult.Failure)
        assertFalse(transport.sentMessages.any { it.contains("DOM.setAttributeValue") })
        assertEquals(DomMutationPhase.IDLE, inspector.mutationState.phase)
    }

    // ============ Text mutation ============

    @Test
    fun setTextNodeValue_updatesText() {
        bindRichLoaded()
        val r = awaitMutation({ cb -> inspector.setTextNodeValue(5, "New text", cb) }) { deliverSuccess("DOM.setNodeValue") }
        assertTrue(r is DomOperationResult.Success)
        assertTrue(transport.sentMessages.any { it.contains("DOM.setNodeValue") && it.contains("\"value\":\"New text\"") })
        assertEquals("New text", inspector.tree[5]?.nodeValue)
    }

    @Test
    fun setTextNodeValue_emptyValue_supported() {
        bindRichLoaded()
        val r = awaitMutation({ cb -> inspector.setTextNodeValue(5, "", cb) }) { deliverSuccess("DOM.setNodeValue") }
        assertTrue(r is DomOperationResult.Success)
        assertEquals("", inspector.tree[5]?.nodeValue)
    }

    @Test
    fun setTextNodeValue_invalidNodeType_rejected() {
        bindRichLoaded()
        transport.resetSentMessages()
        val r = awaitMutation({ cb -> inspector.setTextNodeValue(4, "nope", cb) }) // node 4 is an element
        assertTrue(r is DomOperationResult.Failure)
        assertFalse(transport.sentMessages.any { it.contains("DOM.setNodeValue") })
        assertEquals(DomMutationPhase.IDLE, inspector.mutationState.phase)
    }

    @Test
    fun setTextNodeValue_protocolFailure_keepsOriginalText() {
        bindRichLoaded()
        inspector.setTextNodeValue(5, "changed", { })
        val id = parseIdFor("DOM.setNodeValue")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"fail"}}""")
        waitForMutationSettle()
        assertEquals("Hello World", inspector.tree[5]?.nodeValue)
        assertEquals(DomMutationPhase.FAILURE, inspector.mutationState.phase)
    }

    // ============ Outer HTML ============

    @Test
    fun setOuterHTML_refreshesDocument() {
        bindRichLoaded()
        transport.resetSentMessages()
        inspector.setOuterHTML(4, "<h2 id=\"main\">Replaced</h2>", { })
        // Command sent.
        assertTrue(transport.sentMessages.any { it.contains("DOM.setOuterHTML") })
        val id = parseIdFor("DOM.setOuterHTML")
        // Success triggers a document refresh (DOM.getDocument).
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        waitForGetDocument()
        // Answer the refresh.
        val getDocId = parseIdFor("DOM.getDocument")
        transport.deliverMessage(
            """{"id":$getDocId,"sessionId":"session-1","result":{"root":{
               "nodeId":1,"nodeType":9,"nodeName":"#document","childNodeCount":1,
               "children":[{"nodeId":2,"nodeType":1,"nodeName":"HTML","childNodeCount":1,
                 "children":[{"nodeId":3,"nodeType":1,"nodeName":"BODY","childNodeCount":1,
                   "children":[{"nodeId":40,"nodeType":1,"nodeName":"H2","attributes":["id","main"],"childNodeCount":0}]}]}]}}}"""
        )
        awaitState(DomState.READY)
        // Old node 4 is gone; new node 40 present (re-resolved from Chrome).
        assertNull(inspector.tree[4])
        assertEquals("H2", inspector.tree[40]?.nodeName)
        assertEquals(DomMutationPhase.SUCCESS, inspector.mutationState.phase)
    }

    @Test
    fun setOuterHTML_blankInput_rejected() {
        bindRichLoaded()
        transport.resetSentMessages()
        val r = awaitMutation({ cb -> inspector.setOuterHTML(4, "   ", cb) })
        assertTrue(r is DomOperationResult.Failure)
        assertFalse(transport.sentMessages.any { it.contains("DOM.setOuterHTML") })
    }

    @Test
    fun setOuterHTML_protocolFailure_keepsTree() {
        bindRichLoaded()
        inspector.setOuterHTML(4, "<h2>X</h2>", { })
        val id = parseIdFor("DOM.setOuterHTML")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"invalid html"}}""")
        waitForMutationSettle()
        // No refresh was triggered on failure; local tree unchanged.
        assertNotNull(inspector.tree[4])
        assertEquals(DomMutationPhase.FAILURE, inspector.mutationState.phase)
    }

    // ============ Node deletion ============

    @Test
    fun removeNode_deletesAndUpdatesParent() {
        bindRichLoaded()
        inspector.selectNode(6)
        val r = awaitMutation({ cb -> inspector.removeNode(6, cb) }) { deliverSuccess("DOM.removeNode") }
        assertTrue(r is DomOperationResult.Success)
        assertTrue(transport.sentMessages.any { it.contains("DOM.removeNode") && it.contains("\"nodeId\":6") })
        assertNull(inspector.tree[6])
        // Parent (BODY=3) no longer contains node 6.
        assertFalse(inspector.tree[3]?.children?.any { it.nodeId == 6 } == true)
        // Selection cleared because the deleted node was selected.
        assertNull(inspector.selectedNode())
    }

    @Test
    fun removeNode_documentRoot_rejected() {
        bindRichLoaded()
        transport.resetSentMessages()
        val r = awaitMutation({ cb -> inspector.removeNode(1, cb) })
        assertTrue(r is DomOperationResult.Failure)
        assertFalse(transport.sentMessages.any { it.contains("DOM.removeNode") })
        assertNotNull(inspector.tree.document)
    }

    @Test
    fun removeNode_protocolFailure_keepsNode() {
        bindRichLoaded()
        inspector.removeNode(6, { })
        val id = parseIdFor("DOM.removeNode")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"denied"}}""")
        waitForMutationSettle()
        assertNotNull(inspector.tree[6])
        assertEquals(DomMutationPhase.FAILURE, inspector.mutationState.phase)
    }

    @Test
    fun removeNode_unknownNode_rejected() {
        bindRichLoaded()
        transport.resetSentMessages()
        val r = awaitMutation({ cb -> inspector.removeNode(999, cb) })
        assertTrue(r is DomOperationResult.Failure)
        assertFalse(transport.sentMessages.any { it.contains("DOM.removeNode") })
    }

    // ============ Duplicate submission prevention ============

    @Test
    fun duplicateSubmission_preventedWhilePending() {
        bindRichLoaded()
        inspector.setAttributeValue(4, "id", "a", { })
        val id = parseIdFor("DOM.setAttributeValue")
        // While the first is pending, a second mutation must be rejected.
        val second = AtomicReference<DomOperationResult<Unit>>()
        val latch = CountDownLatch(1)
        inspector.setAttributeValue(4, "class", "b") { r -> second.set(r); latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(second.get() is DomOperationResult.Failure)
        assertEquals(DomMutationPhase.SUBMITTING, inspector.mutationState.phase)
        // Complete the first.
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        waitForMutationSettle()
        assertEquals("a", inspector.tree[4]?.attribute("id"))
        assertEquals(DomMutationPhase.SUCCESS, inspector.mutationState.phase)
    }

    // ============ Stale / lifecycle ============

    @Test
    fun staleResponse_afterDetach_isNotApplied() {
        bindRichLoaded()
        inspector.setAttributeValue(4, "id", "stale", { })
        val id = parseIdFor("DOM.setAttributeValue")
        inspector.detach()
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        waitForMutationSettle()
        // Detached: no tree, no stale application.
        assertEquals(DomState.INACTIVE, inspector.state())
        assertEquals(0, inspector.tree.size)
        assertEquals(DomMutationPhase.IDLE, inspector.mutationState.phase)
    }

    @Test
    fun targetSwitch_clearsMutationState() {
        bindRichLoaded()
        inspector.setAttributeValue(4, "id", "x", { })
        assertEquals(DomMutationPhase.SUBMITTING, inspector.mutationState.phase)
        // Simulate a target switch — bind to a new session clears mutation state.
        inspector.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        assertEquals(DomMutationPhase.IDLE, inspector.mutationState.phase)
        assertNull(inspector.mutationState.error)
    }

    @Test
    fun connectionLoss_invalidatesMutationState() {
        bindRichLoaded()
        inspector.setAttributeValue(4, "id", "x", { })
        assertEquals(DomMutationPhase.SUBMITTING, inspector.mutationState.phase)
        inspector.onConnectionLost()
        assertEquals(DomMutationPhase.IDLE, inspector.mutationState.phase)
        assertEquals(DomState.INACTIVE, inspector.state())
    }

    @Test
    fun selectionChange_doesNotApplyMutationToNewSelection() {
        bindRichLoaded()
        inspector.selectNode(4)
        // Mutate node 6 (DIV). It is not selected.
        inspector.setAttributeValue(6, "id", "new-box", { })
        val id = parseIdFor("DOM.setAttributeValue")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        waitForMutationSettle()
        // Node 6 updated; selection (node 4) untouched.
        assertEquals("new-box", inspector.tree[6]?.attribute("id"))
        assertEquals(4, inspector.selectedNode()?.nodeId)
    }

    private fun waitForMutationSettle() {
        val deadline = System.currentTimeMillis() + 2000
        while (inspector.mutationState.isSubmitting && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }
}
