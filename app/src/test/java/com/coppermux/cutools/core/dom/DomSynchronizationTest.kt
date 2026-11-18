package com.coppermux.cutools.core.dom

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

/**
 * Deterministic Phase 2D tests — live DOM synchronization.
 *
 * Drives the real CommandDispatcher + EventRouter through a fake in-memory
 * transport and applies CDP DOM events synchronously ([DomInspector.applyEventSynchronous])
 * to verify:
 *
 *  - `DOM.setChildNodes` replacement (stale-subtree removal, duplicate prevention)
 *  - `DOM.childNodeInserted` / `DOM.childNodeRemoved` (ordering, parent links, descendants)
 *  - `DOM.attributeModified` / `DOM.attributeRemoved` / `DOM.characterDataModified`
 *  - `DOM.documentUpdated` full reset/rebuild
 *  - external mutation synchronization
 *  - mutation reconciliation (no double application)
 *  - selection / inspection / search synchronization (incl. moved-node retention)
 *  - target switching, disconnect/reconnect, stale-event isolation
 *  - malformed events and unexpected ordering (never crash)
 *
 * These tests never touch Chrome.
 */
class DomSynchronizationTest {

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

    /** An element map payload for DOM events. */
    private fun node(
        id: Int,
        type: Int = 1,
        name: String = "NODE",
        attrs: List<String> = emptyList(),
        backend: Int? = null,
        nodeValue: String? = null,
        childCount: Int = 0,
    ): Map<String, Any?> = buildMap {
        put("nodeId", id)
        put("nodeType", type)
        put("nodeName", name)
        if (backend != null) put("backendNodeId", backend)
        if (attrs.isNotEmpty()) put("attributes", attrs)
        if (nodeValue != null) put("nodeValue", nodeValue)
        put("childNodeCount", childCount)
    }

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
                     {"nodeId":4,"nodeType":1,"nodeName":"H1","backendNodeId":1000,"attributes":["id","main","class","hero"],"childNodeCount":1,
                       "children":[{"nodeId":5,"nodeType":3,"nodeName":"#text","nodeValue":"Hello World","childNodeCount":0}]},
                     {"nodeId":6,"nodeType":1,"nodeName":"DIV","attributes":["id","box"],"childNodeCount":0}
                   ]}]}]}}}"""
        )
        awaitState(DomState.READY)
    }

    /** A replacement document (used by documentUpdated rebuild tests). */
    private fun reloadDocumentWith(newDoc: String) {
        waitForGetDocument()
        val getDocId = parseLastSentId()
        transport.deliverMessage("""{"id":$getDocId,"sessionId":"session-1","result":{"root":$newDoc}}""")
        awaitState(DomState.READY)
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.first { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun parseLastSentId(): Long {
        val raw = transport.sentMessages.last()
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitForGetDocument() {
        val deadline = System.currentTimeMillis() + 3000
        while (!transport.sentMessages.any { it.contains("DOM.getDocument") } && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(transport.sentMessages.any { it.contains("DOM.getDocument") })
    }

    private fun awaitState(expected: DomState) {
        val deadline = System.currentTimeMillis() + 3000
        while (inspector.state() != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(expected, inspector.state())
    }

    private fun apply(method: String, params: Map<*, *>, sessionId: String? = null) {
        inspector.applyEventSynchronous(method, params, sessionId)
    }

    private fun deliverSuccess(method: String) {
        val id = parseIdFor(method)
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
    }

    private fun awaitOuterHtml() {
        val deadline = System.currentTimeMillis() + 3000
        while (inspector.inspectionState.outerHtml == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertNotNull(inspector.inspectionState.outerHtml)
    }

    // =====================================================================
    // setChildNodes
    // =====================================================================

    @Test
    fun setChildNodes_replacesChildrenAndRemovesStaleNodes() {
        bindRichLoaded()
        apply(
            "DOM.setChildNodes",
            mapOf(
                "parentId" to 3,
                "nodes" to listOf(node(30, name = "P"), node(31, name = "SPAN")),
            ),
        )
        assertEquals(2, inspector.tree[3]?.children?.size)
        assertTrue(inspector.tree.contains(30))
        assertTrue(inspector.tree.contains(31))
        // Stale children (4, 5, 6) removed.
        assertNull(inspector.tree[4])
        assertNull(inspector.tree[5])
        assertNull(inspector.tree[6])
        // Parent relationships established.
        assertEquals(3, inspector.tree.parentOf(30)?.nodeId)
        assertEquals(3, inspector.tree.parentOf(31)?.nodeId)
    }

    @Test
    fun setChildNodes_removesStaleSubtrees_notJustDirectChildren() {
        bindRichLoaded()
        // Replacing BODY's children removes H1 AND its loaded text child (5).
        apply("DOM.setChildNodes", mapOf("parentId" to 3, "nodes" to listOf(node(30, name = "P"))))
        assertNull(inspector.tree[4])
        assertNull(inspector.tree[5])
        assertNull(inspector.tree[6])
        assertTrue(inspector.tree.contains(30))
    }

    @Test
    fun setChildNodes_idempotent_noDuplicates() {
        bindRichLoaded()
        val children = listOf(node(30, name = "P"), node(31, name = "SPAN"))
        apply("DOM.setChildNodes", mapOf("parentId" to 3, "nodes" to children))
        apply("DOM.setChildNodes", mapOf("parentId" to 3, "nodes" to children))
        assertEquals(2, inspector.tree[3]?.children?.size)
        assertEquals(2, inspector.tree.allNodes.count { it.nodeId == 30 || it.nodeId == 31 })
    }

    @Test
    fun setChildNodes_unknownParent_isSafeNoOp() {
        bindRichLoaded()
        val before = inspector.tree.size
        apply("DOM.setChildNodes", mapOf("parentId" to 999, "nodes" to listOf(node(30, name = "P"))))
        assertEquals(before, inspector.tree.size)
        assertFalse(inspector.tree.contains(30))
    }

    @Test
    fun setChildNodes_malformedNodes_doesNotWipeChildren() {
        bindRichLoaded()
        apply("DOM.setChildNodes", mapOf("parentId" to 3, "nodes" to listOf("garbage", 42)))
        // Non-empty but fully-malformed payload must not wipe existing children.
        assertEquals(2, inspector.tree[3]?.children?.size)
        assertTrue(inspector.tree.contains(4))
    }

    // =====================================================================
    // childNodeInserted
    // =====================================================================

    @Test
    fun childNodeInserted_placesAtCorrectIndex() {
        bindRichLoaded()
        // BODY children: [H1(4), DIV(6)]. Insert P after H1 → [H1, P, DIV].
        apply(
            "DOM.childNodeInserted",
            mapOf("parentNodeId" to 3, "previousNodeId" to 4, "node" to node(30, name = "P")),
        )
        assertEquals(listOf(4, 30, 6), inspector.tree[3]?.children?.map { it.nodeId })
        assertEquals(3, inspector.tree.parentOf(30)?.nodeId)
    }

    @Test
    fun childNodeInserted_atStart_whenNoPreviousSibling() {
        bindRichLoaded()
        apply(
            "DOM.childNodeInserted",
            mapOf("parentNodeId" to 3, "previousNodeId" to 0, "node" to node(30, name = "P")),
        )
        assertEquals(listOf(30, 4, 6), inspector.tree[3]?.children?.map { it.nodeId })
    }

    @Test
    fun childNodeInserted_unknownPreviousSibling_appends() {
        bindRichLoaded()
        apply(
            "DOM.childNodeInserted",
            mapOf("parentNodeId" to 3, "previousNodeId" to 999, "node" to node(30, name = "P")),
        )
        assertEquals(listOf(4, 6, 30), inspector.tree[3]?.children?.map { it.nodeId })
    }

    @Test
    fun childNodeInserted_existingNode_doesNotDuplicate() {
        bindRichLoaded()
        // Insert a node that already exists under the parent (H1=4) — no duplicate.
        apply(
            "DOM.childNodeInserted",
            mapOf("parentNodeId" to 3, "previousNodeId" to 0, "node" to node(4, name = "H1")),
        )
        assertEquals(2, inspector.tree[3]?.children?.size)
        assertEquals(1, inspector.tree.allNodes.count { it.nodeId == 4 })
    }

    @Test
    fun childNodeInserted_unknownParent_isSafeNoOp() {
        bindRichLoaded()
        apply(
            "DOM.childNodeInserted",
            mapOf("parentNodeId" to 999, "previousNodeId" to 0, "node" to node(30, name = "P")),
        )
        assertFalse(inspector.tree.contains(30))
    }

    @Test
    fun childNodeInserted_malformedNode_isSafeNoOp() {
        bindRichLoaded()
        apply("DOM.childNodeInserted", mapOf("parentNodeId" to 3, "previousNodeId" to 0, "node" to "not-a-node"))
        assertFalse(inspector.tree.contains(30))
        assertEquals(2, inspector.tree[3]?.children?.size)
    }

    // =====================================================================
    // childNodeRemoved
    // =====================================================================

    @Test
    fun childNodeRemoved_removesNodeAndSubtree() {
        bindRichLoaded()
        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 4))
        assertNull(inspector.tree[4])
        assertNull(inspector.tree[5]) // descendant removed too
        assertTrue(inspector.tree.contains(6))
        assertEquals(listOf(6), inspector.tree[3]?.children?.map { it.nodeId })
    }

    @Test
    fun childNodeRemoved_clearsSelectionIfSelected() {
        bindRichLoaded()
        inspector.selectNode(4)
        assertEquals(4, inspector.selectedNode()?.nodeId)
        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 4))
        assertNull(inspector.selectedNode())
        assertFalse(inspector.inspectionState.isSelected)
    }

    @Test
    fun childNodeRemoved_unknownNode_isSafeNoOp() {
        bindRichLoaded()
        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 999))
        assertEquals(6, inspector.tree.size)
    }

    @Test
    fun childNodeRemoved_alreadyRemoved_isSafeNoOp() {
        bindRichLoaded()
        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 4))
        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 4))
        assertNull(inspector.tree[4])
        assertTrue(inspector.tree.contains(6))
    }

    // =====================================================================
    // attributeModified / attributeRemoved / characterDataModified
    // =====================================================================

    @Test
    fun attributeModified_updatesExactlyThatAttribute() {
        bindRichLoaded()
        apply("DOM.attributeModified", mapOf("nodeId" to 4, "name" to "id", "value" to "renamed"))
        assertEquals("renamed", inspector.tree[4]?.attribute("id"))
        assertEquals("hero", inspector.tree[4]?.attribute("class"))
        assertEquals(2, inspector.tree[4]?.attributes?.size)
    }

    @Test
    fun attributeModified_addsNewAttribute() {
        bindRichLoaded()
        apply("DOM.attributeModified", mapOf("nodeId" to 4, "name" to "data-x", "value" to "42"))
        assertEquals("42", inspector.tree[4]?.attribute("data-x"))
        assertEquals("main", inspector.tree[4]?.attribute("id"))
    }

    @Test
    fun attributeModified_selectedNode_refreshesInspectionAndInvalidatesHtml() {
        bindRichLoaded()
        inspector.selectNode(4)
        inspector.refreshOuterHTML()
        val id = parseIdFor("DOM.getOuterHTML")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"outerHTML":"<h1>X</h1>"}}""")
        awaitOuterHtml()
        assertNotNull(inspector.inspectionState.outerHtml)

        apply("DOM.attributeModified", mapOf("nodeId" to 4, "name" to "id", "value" to "zzz"))
        assertEquals("zzz", inspector.inspectionState.node?.attribute("id"))
        assertNull("Cached outer HTML must be invalidated", inspector.inspectionState.outerHtml)
    }

    @Test
    fun attributeModified_unknownNode_isSafeNoOp() {
        bindRichLoaded()
        apply("DOM.attributeModified", mapOf("nodeId" to 999, "name" to "id", "value" to "x"))
        assertTrue(inspector.tree.contains(4))
    }

    @Test
    fun attributeRemoved_removesExactlyThatAttribute() {
        bindRichLoaded()
        apply("DOM.attributeRemoved", mapOf("nodeId" to 4, "name" to "id"))
        assertNull(inspector.tree[4]?.attribute("id"))
        assertEquals("hero", inspector.tree[4]?.attribute("class"))
        assertEquals(1, inspector.tree[4]?.attributes?.size)
    }

    @Test
    fun attributeRemoved_selectedNode_refreshesInspectionAndInvalidatesHtml() {
        bindRichLoaded()
        inspector.selectNode(4)
        inspector.refreshOuterHTML()
        val id = parseIdFor("DOM.getOuterHTML")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"outerHTML":"<h1>X</h1>"}}""")
        awaitOuterHtml()
        apply("DOM.attributeRemoved", mapOf("nodeId" to 4, "name" to "class"))
        assertNull(inspector.inspectionState.node?.attribute("class"))
        assertNull(inspector.inspectionState.outerHtml)
    }

    @Test
    fun attributeRemoved_unknownNode_isSafeNoOp() {
        bindRichLoaded()
        apply("DOM.attributeRemoved", mapOf("nodeId" to 999, "name" to "id"))
        assertEquals("main", inspector.tree[4]?.attribute("id"))
    }

    @Test
    fun characterDataModified_updatesTextNodeValue() {
        bindRichLoaded()
        apply("DOM.characterDataModified", mapOf("nodeId" to 5, "characterData" to "Changed"))
        assertEquals("Changed", inspector.tree[5]?.nodeValue)
    }

    @Test
    fun characterDataModified_selectedTextNode_refreshesInspectionAndInvalidatesHtml() {
        bindRichLoaded()
        inspector.selectNode(5)
        apply("DOM.characterDataModified", mapOf("nodeId" to 5, "characterData" to "New"))
        assertEquals("New", inspector.inspectionState.node?.nodeValue)
    }

    @Test
    fun characterDataModified_unknownNode_isSafeNoOp() {
        bindRichLoaded()
        apply("DOM.characterDataModified", mapOf("nodeId" to 999, "characterData" to "x"))
        assertEquals("Hello World", inspector.tree[5]?.nodeValue)
    }

    // =====================================================================
    // documentUpdated
    // =====================================================================

    @Test
    fun documentUpdated_discardsTreeClearsStateAndRebuilds() {
        bindRichLoaded()
        inspector.selectNode(4)
        inspector.performSearch("id")
        awaitSearchComplete()
        assertTrue(inspector.searchState.hasResults)
        assertTrue(inspector.inspectionState.isSelected)

        transport.resetSentMessages()
        apply("DOM.documentUpdated", mapOf<String, Any?>())
        // Old tree discarded, state cleared.
        assertEquals(DomState.LOADING, inspector.state())
        assertFalse(inspector.inspectionState.isSelected)
        assertFalse(inspector.searchState.hasResults)

        reloadDocumentWith(
            """{"nodeId":1,"nodeType":9,"nodeName":"#document","childNodeCount":1,
                "children":[{"nodeId":2,"nodeType":1,"nodeName":"HTML","childNodeCount":1,
                  "children":[{"nodeId":3,"nodeType":1,"nodeName":"BODY","childNodeCount":1,
                    "children":[{"nodeId":30,"nodeType":1,"nodeName":"P","childNodeCount":0}]}]}]}"""
        )
        assertNotNull(inspector.tree.document)
        assertTrue(inspector.tree.contains(30))
        assertNull("Old document nodes must not survive", inspector.tree[4])
        assertEquals(1, inspector.tree.allNodes.count { it.nodeType == DomNodeType.DOCUMENT })
    }

    // =====================================================================
    // external mutation synchronization
    // =====================================================================

    @Test
    fun externalInsertion_appearsWithoutRefresh() {
        bindRichLoaded()
        apply(
            "DOM.childNodeInserted",
            mapOf("parentNodeId" to 3, "previousNodeId" to 6, "node" to node(31, name = "SECTION")),
        )
        val inserted = inspector.tree[31]
        assertNotNull("Externally inserted node must appear", inserted)
        assertEquals("SECTION", inserted?.nodeName)
        assertEquals(listOf(4, 6, 31), inspector.tree[3]?.children?.map { it.nodeId })
    }

    @Test
    fun externalRemoval_appearsWithoutRefresh() {
        bindRichLoaded()
        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 6))
        assertNull("Externally removed node must disappear", inspector.tree[6])
    }

    @Test
    fun externalAttributeChange_appearsWithoutRefresh() {
        bindRichLoaded()
        apply("DOM.attributeModified", mapOf("nodeId" to 6, "name" to "id", "value" to "new-box"))
        assertEquals("new-box", inspector.tree[6]?.attribute("id"))
    }

    @Test
    fun externalTextChange_appearsWithoutRefresh() {
        bindRichLoaded()
        apply("DOM.characterDataModified", mapOf("nodeId" to 5, "characterData" to "External text"))
        assertEquals("External text", inspector.tree[5]?.nodeValue)
    }

    // =====================================================================
    // mutation reconciliation
    // =====================================================================

    @Test
    fun cuToolsAttributeMutation_followedByEvent_noDoubleApplication() {
        bindRichLoaded()
        val latch = CountDownLatch(1)
        inspector.setAttributeValue(4, "id", "new-main") { latch.countDown() }
        deliverSuccess("DOM.setAttributeValue")
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertEquals("new-main", inspector.tree[4]?.attribute("id"))

        // Chrome also emits attributeModified — applying it must not duplicate.
        apply("DOM.attributeModified", mapOf("nodeId" to 4, "name" to "id", "value" to "new-main"))
        assertEquals(1, inspector.tree[4]!!.attributes.count { it.name == "id" })
        assertEquals("new-main", inspector.tree[4]?.attribute("id"))
    }

    @Test
    fun cuToolsDeletion_followedByChildNodeRemoved_noDoubleRemoval() {
        bindRichLoaded()
        inspector.selectNode(6)
        val latch = CountDownLatch(1)
        inspector.removeNode(6) { latch.countDown() }
        deliverSuccess("DOM.removeNode")
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertNull(inspector.tree[6])
        assertNull(inspector.selectedNode())

        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 6))
        assertNull(inspector.tree[6])
        assertEquals(0, inspector.tree[3]!!.children.count { it.nodeId == 6 })
    }

    @Test
    fun cuToolsTextMutation_followedByCharacterDataEvent_noDoubleApplication() {
        bindRichLoaded()
        val latch = CountDownLatch(1)
        inspector.setTextNodeValue(5, "Changed") { latch.countDown() }
        deliverSuccess("DOM.setNodeValue")
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertEquals("Changed", inspector.tree[5]?.nodeValue)

        apply("DOM.characterDataModified", mapOf("nodeId" to 5, "characterData" to "Changed"))
        assertEquals("Changed", inspector.tree[5]?.nodeValue)
    }

    @Test
    fun outerHtmlReplacement_newNodeIdentity_isNotOldNode() {
        bindRichLoaded()
        inspector.selectNode(4)
        val latch = CountDownLatch(1)
        inspector.setOuterHTML(4, "<h2 id=\"new\">Replaced</h2>") { latch.countDown() }
        val htmlId = parseIdFor("DOM.setOuterHTML")
        transport.deliverMessage("""{"id":$htmlId,"sessionId":"session-1","result":{}}""")
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        // Success triggers a document refresh.
        reloadDocumentWith(
            """{"nodeId":1,"nodeType":9,"nodeName":"#document","childNodeCount":1,
                "children":[{"nodeId":2,"nodeType":1,"nodeName":"HTML","childNodeCount":1,
                  "children":[{"nodeId":3,"nodeType":1,"nodeName":"BODY","childNodeCount":1,
                    "children":[{"nodeId":40,"nodeType":1,"nodeName":"H2","attributes":["id","new"],"childNodeCount":0}]}]}]}"""
        )
        // The old node (4) is gone; a NEW node (40) exists. Selection is not
        // pretended to be the old node.
        assertNull(inspector.tree[4])
        assertEquals("H2", inspector.tree[40]?.nodeName)
        assertNull("Selection must not pretend the old node remains selected", inspector.selectedNode())
    }

    @Test
    fun movedNode_sameBackendId_retainsSelection() {
        bindRichLoaded()
        inspector.selectNode(4)
        assertEquals(4, inspector.selectedNode()?.nodeId)

        // Node moves: removed then re-inserted with the same backendNodeId (1000).
        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 4))
        assertNull("Selection cleared while the node is absent", inspector.selectedNode())

        apply(
            "DOM.childNodeInserted",
            mapOf("parentNodeId" to 3, "previousNodeId" to 0, "node" to node(40, name = "H1", backend = 1000, attrs = listOf("id", "main"))),
        )
        assertEquals("Same node moved — selection retained on its new nodeId", 40, inspector.selectedNode()?.nodeId)
        assertEquals(40, inspector.inspectionState.node?.nodeId)
    }

    @Test
    fun replacementNode_differentBackendId_doesNotRestoreSelection() {
        bindRichLoaded()
        inspector.selectNode(4)
        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 4))
        assertNull(inspector.selectedNode())

        // A replacement node with a different backendNodeId is NOT the old node.
        apply(
            "DOM.childNodeInserted",
            mapOf("parentNodeId" to 3, "previousNodeId" to 0, "node" to node(40, name = "H1", backend = 2000)),
        )
        assertNull("Replacement node must not be treated as the old selection", inspector.selectedNode())
    }

    // =====================================================================
    // search synchronization
    // =====================================================================

    @Test
    fun searchResult_deleted_removedFromResults() {
        bindRichLoaded()
        inspector.performSearch("#box")
        awaitSearchComplete()
        assertEquals(1, inspector.searchState.resultCount)
        assertEquals(6, inspector.searchState.activeResult?.nodeId)

        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 6))
        assertEquals(0, inspector.searchState.resultCount)
        assertEquals(-1, inspector.searchState.activeIndex)
    }

    @Test
    fun searchResult_changedAttribute_noLongerMatches() {
        bindRichLoaded()
        inspector.performSearch("id=box")
        awaitSearchComplete()
        assertEquals(1, inspector.searchState.resultCount)

        apply("DOM.attributeModified", mapOf("nodeId" to 6, "name" to "id", "value" to "other"))
        assertEquals("Changed result must be re-evaluated", 0, inspector.searchState.resultCount)
    }

    @Test
    fun searchResult_newMatch_appearsWithoutRefresh() {
        bindRichLoaded()
        inspector.performSearch("data-x")
        awaitSearchComplete()
        assertEquals(0, inspector.searchState.resultCount)

        apply("DOM.attributeModified", mapOf("nodeId" to 4, "name" to "data-x", "value" to "42"))
        assertEquals(1, inspector.searchState.resultCount)
        assertEquals(4, inspector.searchState.activeResult?.nodeId)
    }

    @Test
    fun search_activeResultDeleted_activeIndexStaysValid() {
        bindRichLoaded()
        inspector.performSearch("id")
        awaitSearchComplete()
        assertTrue(inspector.searchState.resultCount >= 3)

        // Remove DIV (6) — one of the results.
        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 6))
        assertFalse(inspector.searchState.results.any { it.nodeId == 6 })
        assertTrue(inspector.searchState.activeIndex in -1 until inspector.searchState.resultCount)
    }

    // =====================================================================
    // selection synchronization
    // =====================================================================

    @Test
    fun selectedNode_modified_inspectionRefreshes() {
        bindRichLoaded()
        inspector.selectNode(4)
        apply("DOM.attributeModified", mapOf("nodeId" to 4, "name" to "class", "value" to "new-hero"))
        assertEquals("new-hero", inspector.inspectionState.node?.attribute("class"))
        assertEquals("main", inspector.inspectionState.node?.attribute("id"))
    }

    @Test
    fun selectedNode_removed_clearsSelection() {
        bindRichLoaded()
        inspector.selectNode(4)
        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 3, "nodeId" to 4))
        assertNull(inspector.selectedNode())
        assertFalse(inspector.inspectionState.isSelected)
    }

    @Test
    fun selectedNode_staysSelected_whenUnchangedSetChildNodesArrives() {
        bindRichLoaded()
        inspector.selectNode(4)
        apply(
            "DOM.setChildNodes",
            mapOf(
                "parentId" to 3,
                "nodes" to listOf(node(4, name = "H1"), node(6, name = "DIV")),
            ),
        )
        // Node 4 still present → selection retained.
        assertEquals(4, inspector.selectedNode()?.nodeId)
    }

    // =====================================================================
    // lifecycle / target safety
    // =====================================================================

    @Test
    fun eventFromOldSession_isIgnored() {
        bindRichLoaded()
        apply(
            "DOM.childNodeInserted",
            mapOf("parentNodeId" to 3, "previousNodeId" to 0, "node" to node(30, name = "P")),
            sessionId = "old-session",
        )
        assertFalse("Event from another session must not mutate the tree", inspector.tree.contains(30))
        assertEquals(6, inspector.tree.size)
    }

    @Test
    fun targetSwitch_clearsState_andIgnoresOldSessionEvents() {
        bindRichLoaded()
        inspector.selectNode(4)
        inspector.performSearch("id")
        awaitSearchComplete()

        // Bind to a fresh session (target switch).
        inspector.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        assertFalse(inspector.inspectionState.isSelected)
        assertFalse(inspector.searchState.hasResults)
        assertEquals(0, inspector.tree.size)
        assertEquals(DomState.LOADING, inspector.state())

        // An event from the OLD session must not mutate the new tree.
        apply("DOM.childNodeInserted", mapOf("parentNodeId" to 3, "previousNodeId" to 0, "node" to node(30, name = "P")), sessionId = "session-1")
        assertEquals(0, inspector.tree.size)
    }

    @Test
    fun connectionLoss_clearsEverything() {
        bindRichLoaded()
        inspector.selectNode(4)
        inspector.performSearch("id")
        awaitSearchComplete()
        inspector.onConnectionLost()
        assertEquals(DomState.INACTIVE, inspector.state())
        assertEquals(0, inspector.tree.size)
        assertNull(inspector.session)
        assertNull(inspector.selectedNode())
        assertFalse(inspector.searchState.hasResults)
    }

    @Test
    fun reconnect_rebuildsCleanTree_withoutDuplicates() {
        bindRichLoaded()
        inspector.onConnectionLost()
        assertEquals(0, inspector.tree.size)

        transport.resetSentMessages()
        inspector.bindSession(session)
        val enableId = parseIdFor("DOM.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitForGetDocument()
        reloadDocumentWith(
            """{"nodeId":1,"nodeType":9,"nodeName":"#document","childNodeCount":1,
                "children":[{"nodeId":2,"nodeType":1,"nodeName":"HTML","childNodeCount":1,
                  "children":[{"nodeId":3,"nodeType":1,"nodeName":"BODY","childNodeCount":0}]}]}"""
        )
        assertNotNull(inspector.tree.document)
        assertEquals("Exactly one document root", 1, inspector.tree.allNodes.count { it.nodeType == DomNodeType.DOCUMENT })
        assertEquals(1, inspector.tree.allNodes.count { it.nodeId == 1 })
    }

    // =====================================================================
    // robustness / malformed events / unexpected ordering
    // =====================================================================

    @Test
    fun malformedEvents_neverCrash() {
        bindRichLoaded()
        // Null/absent params fields for every event type.
        apply("DOM.childNodeInserted", mapOf("parentNodeId" to 3))
        apply("DOM.childNodeInserted", mapOf("parentNodeId" to 3, "node" to emptyMap<String, Any?>()))
        apply("DOM.childNodeRemoved", emptyMap<String, Any?>())
        apply("DOM.attributeModified", mapOf("nodeId" to 4))
        apply("DOM.attributeRemoved", emptyMap<String, Any?>())
        apply("DOM.characterDataModified", mapOf("nodeId" to 5))
        apply("DOM.setChildNodes", mapOf("parentId" to 3))
        // documentUpdated is a valid (non-malformed) event and is covered by its
        // dedicated test; it is intentionally not included here.
        // Tree remains usable.
        assertTrue(inspector.tree.contains(4))
        assertTrue(inspector.tree.contains(5))
    }

    @Test
    fun unexpectedOrdering_noCrash() {
        bindRichLoaded()
        // childNodeInserted for a parent whose children were never loaded: the
        // parent (node 2 HTML) has 1 loaded child; insert into an unknown node.
        apply(
            "DOM.childNodeInserted",
            mapOf("parentNodeId" to 50, "previousNodeId" to 0, "node" to node(51, name = "P")),
        )
        // attributeModified before the node is known.
        apply("DOM.attributeModified", mapOf("nodeId" to 51, "name" to "id", "value" to "x"))
        // childNodeRemoved before the node is known.
        apply("DOM.childNodeRemoved", mapOf("parentNodeId" to 50, "nodeId" to 51))
        assertFalse(inspector.tree.contains(51))
        assertTrue(inspector.tree.contains(4))
    }

    @Test
    fun insertThenSetChildNodes_reconcilesWithoutDuplicates() {
        bindRichLoaded()
        // Insert node 30, then a full authoritative set including it.
        apply(
            "DOM.childNodeInserted",
            mapOf("parentNodeId" to 3, "previousNodeId" to 4, "node" to node(30, name = "P")),
        )
        assertEquals(3, inspector.tree[3]?.children?.size)
        apply(
            "DOM.setChildNodes",
            mapOf("parentNodeId" to 3, "nodes" to listOf(node(4, name = "H1"), node(30, name = "P"), node(6, name = "DIV"))),
        )
        assertEquals(listOf(4, 30, 6), inspector.tree[3]?.children?.map { it.nodeId })
        assertEquals(1, inspector.tree.allNodes.count { it.nodeId == 30 })
    }

    private fun awaitSearchComplete() {
        val deadline = System.currentTimeMillis() + 3000
        while (inspector.searchState.loading && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }
}
