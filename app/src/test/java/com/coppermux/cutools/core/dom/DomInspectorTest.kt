package com.coppermux.cutools.core.dom

import com.coppermux.cutools.core.error.ProtocolException
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
 * Deterministic unit tests for [DomInspector] using a fake in-memory transport.
 * These never touch Chrome; they drive the real CommandDispatcher + EventRouter
 * and simulate protocol behavior to verify inspection logic.
 *
 * Phase 2A is read-only: the coverage focuses on DOM.enable, DOM.getDocument,
 * DOM.requestChildNodes, response parsing, selection, and lifecycle. DOM
 * mutation commands are intentionally not covered here.
 */
class DomInspectorTest {

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

    /** Simulates a successful DOM.getDocument response and waits for READY state. */
    private fun simulateDocumentLoaded() {
        // Respond to DOM.getDocument. BODY has childNodeCount=2 but NO inline
        // children, so it is lazily loaded via requestChildNodes on expansion.
        val getDocId = parseLastSentId()
        transport.deliverMessage(
            """{"id":$getDocId,"sessionId":"session-1","result":{"root":{
               "nodeId":1,"nodeType":9,"nodeName":"#document","childNodeCount":1,
               "children":[{"nodeId":2,"nodeType":1,"nodeName":"HTML","childNodeCount":1,
                 "children":[{"nodeId":3,"nodeType":1,"nodeName":"BODY","childNodeCount":2}]}]}}}"""
        )
        awaitState(DomState.READY)
    }

    private fun parseLastSentId(): Long {
        val raw = transport.sentMessages.last()
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun awaitState(expected: DomState) {
        val deadline = System.currentTimeMillis() + 3000
        while (inspector.state() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(expected, inspector.state())
    }

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.first { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun waitForGetDocument() {
        val deadline = System.currentTimeMillis() + 3000
        while (!transport.sentMessages.any { it.contains("DOM.getDocument") } && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(transport.sentMessages.any { it.contains("DOM.getDocument") })
    }

    /** Binds and fully loads the document into READY state. */
    private fun bindLoaded() {
        inspector.bindSession(session)
        val enableId = parseIdFor("DOM.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitForGetDocument()
        simulateDocumentLoaded()
    }

    @Test
    fun bindSession_sendsDomEnableThenGetDocument_andLoads() {
        inspector.bindSession(session)
        // DOM.enable sent
        assertTrue(transport.sentMessages.any { it.contains("DOM.enable") })
        // simulate enable success, then getDocument response loaded above
        val enableId = parseIdFor("DOM.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitForGetDocument()
        simulateDocumentLoaded()
        assertEquals(DomState.READY, inspector.state())
        assertEquals(3, inspector.tree.size)
        assertEquals(1, inspector.tree.documentNodeId)
        assertNotNull(inspector.tree.document)
    }

    @Test
    fun bindSession_handlesEnableFailure() {
        var errorRef: Throwable? = null
        val listener = object : DomInspectorListener {
            override fun onStateChanged(state: DomState, error: Throwable?) { if (state == DomState.ERROR) errorRef = error }
            override fun onDocumentLoaded(document: DomNode?) {}
            override fun onNodesChanged(changedNodeIds: Set<Int>) {}
            override fun onSelectionChanged(node: DomNode?) {}
            override fun onInspectionStateChanged(state: com.coppermux.cutools.core.dom.DomInspectionState) {}
            override fun onSearchStateChanged(state: com.coppermux.cutools.core.dom.DomSearchState) {}
            override fun onError(error: Throwable) {}
        }
        inspector.addListener(listener)
        inspector.bindSession(session)
        val enableId = parseIdFor("DOM.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        awaitState(DomState.ERROR)
        assertNotNull(errorRef)
    }

    @Test
    fun bindSession_handlesGetDocumentMissingRoot() {
        val errorRef = AtomicReference<Throwable?>()
        inspector.addListener(object : DomInspectorListener {
            override fun onStateChanged(state: DomState, error: Throwable?) { if (state == DomState.ERROR) errorRef.set(error) }
            override fun onDocumentLoaded(document: DomNode?) {}
            override fun onNodesChanged(changedNodeIds: Set<Int>) {}
            override fun onSelectionChanged(node: DomNode?) {}
            override fun onInspectionStateChanged(state: com.coppermux.cutools.core.dom.DomInspectionState) {}
            override fun onSearchStateChanged(state: com.coppermux.cutools.core.dom.DomSearchState) {}
            override fun onError(error: Throwable) {}
        })
        inspector.bindSession(session)
        val enableId = parseIdFor("DOM.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitForGetDocument()
        val getDocId = parseIdFor("DOM.getDocument")
        transport.deliverMessage("""{"id":$getDocId,"sessionId":"session-1","result":{}}""")
        awaitState(DomState.ERROR)
        assertNotNull(errorRef.get())
    }

    @Test
    fun selectNode_setsSelectionAndSendsInspectedNode() {
        bindLoaded()
        inspector.selectNode(3)
        assertEquals(3, inspector.selectedNode()?.nodeId)
        assertTrue(transport.sentMessages.any { it.contains("DOM.setInspectedNode") && it.contains("\"nodeId\":3") })
    }

    @Test
    fun selectNode_unknownNode_reportsError_doesNotSelect() {
        bindLoaded()
        var errorRef: Throwable? = null
        inspector.addListener(object : DomInspectorListener {
            override fun onStateChanged(state: DomState, error: Throwable?) {}
            override fun onDocumentLoaded(document: DomNode?) {}
            override fun onNodesChanged(changedNodeIds: Set<Int>) {}
            override fun onSelectionChanged(node: DomNode?) {}
            override fun onInspectionStateChanged(state: com.coppermux.cutools.core.dom.DomInspectionState) {}
            override fun onSearchStateChanged(state: com.coppermux.cutools.core.dom.DomSearchState) {}
            override fun onError(error: Throwable) { errorRef = error }
        })
        inspector.selectNode(999)
        assertNull(inspector.selectedNode())
        assertNotNull(errorRef)
    }

    @Test
    fun clearSelection_clearsSelectedNode() {
        bindLoaded()
        inspector.selectNode(3)
        assertEquals(3, inspector.selectedNode()?.nodeId)
        inspector.clearSelection()
        assertNull(inspector.selectedNode())
    }

    @Test
    fun expandNode_sendsRequestChildNodes() {
        bindLoaded()
        transport.resetSentMessages()
        // BODY (3) has childNodeCount=2 but no loaded children → fetches.
        inspector.expandNode(3)
        assertTrue(transport.sentMessages.any { it.contains("DOM.requestChildNodes") })
        val sent = transport.sentMessages.first { it.contains("DOM.requestChildNodes") }
        assertTrue(sent.contains("\"nodeId\":3"))
    }

    @Test
    fun expandNode_alreadyLoaded_doesNotResend() {
        bindLoaded()
        transport.resetSentMessages()
        // HTML (2) has 1 loaded inline child (BODY) and childCount 1 → nothing to fetch.
        inspector.expandNode(2)
        assertFalse(transport.sentMessages.any { it.contains("DOM.requestChildNodes") })
        assertTrue(inspector.isExpanded(2))
    }

    @Test
    fun collapseNode_updatesExpansionState() {
        bindLoaded()
        transport.resetSentMessages()
        inspector.expandNode(2)
        assertTrue(inspector.isExpanded(2))
        inspector.collapseNode(2)
        assertFalse(inspector.isExpanded(2))
    }

    @Test
    fun getOuterHTML_returnsHtml() {
        bindLoaded()
        inspector.selectNode(3)
        transport.resetSentMessages()
        val result = AtomicReference<DomOperationResult<String>>()
        val latch = CountDownLatch(1)
        inspector.getOuterHTML { r -> result.set(r); latch.countDown() }
        assertTrue(transport.sentMessages.any { it.contains("DOM.getOuterHTML") })
        val id = parseIdFor("DOM.getOuterHTML")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"outerHTML":"<body>hi</body>"}}""")
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(result.get() is DomOperationResult.Success)
        assertEquals("<body>hi</body>", (result.get() as DomOperationResult.Success).value)
    }

    @Test
    fun search_byTag_findsMatches() {
        bindLoaded()
        val result = AtomicReference<DomOperationResult<List<DomNode>>>()
        val latch = CountDownLatch(1)
        inspector.search("BODY") { r -> result.set(r); latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val list = (result.get() as DomOperationResult.Success).value
        assertEquals(1, list.size)
        assertEquals(3, list[0].nodeId)
    }

    @Test
    fun search_emptyQuery_returnsEmpty() {
        bindLoaded()
        val result = AtomicReference<DomOperationResult<List<DomNode>>>()
        val latch = CountDownLatch(1)
        inspector.search("   ") { r -> result.set(r); latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue((result.get() as DomOperationResult.Success).value.isEmpty())
    }

    @Test
    fun search_notAttached_returnsFailure() {
        val result = AtomicReference<DomOperationResult<List<DomNode>>>()
        val latch = CountDownLatch(1)
        inspector.search("div") { r -> result.set(r); latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(result.get() is DomOperationResult.Failure)
    }

    // ============ expansion events ============

    @Test
    fun event_setChildNodes_replacesChildren() {
        bindLoaded()
        // BODY(node 3) gets 2 children now
        router.handleEvent(Event(
            "DOM.setChildNodes",
            mapOf(
                "parentId" to 3,
                "nodes" to listOf(
                    mapOf("nodeId" to 30, "nodeType" to 1, "nodeName" to "P"),
                    mapOf("nodeId" to 31, "nodeType" to 1, "nodeName" to "SPAN"),
                ),
            ),
            sessionId = session.sessionId,
        ))
        val deadline = System.currentTimeMillis() + 2000
        while (inspector.tree[3]?.children?.size != 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2, inspector.tree[3]?.children?.size)
        assertTrue(inspector.tree.contains(30))
        assertTrue(inspector.tree.contains(31))
        assertEquals(3, inspector.tree.parentOf(30)?.nodeId)
    }

    @Test
    fun event_setChildNodes_idempotent_noDuplicates() {
        bindLoaded()
        val nodes = listOf(
            mapOf("nodeId" to 30, "nodeType" to 1, "nodeName" to "P"),
            mapOf("nodeId" to 31, "nodeType" to 1, "nodeName" to "SPAN"),
        )
        router.handleEvent(Event("DOM.setChildNodes", mapOf("parentId" to 3, "nodes" to nodes), sessionId = session.sessionId))
        val deadline = System.currentTimeMillis() + 2000
        while (inspector.tree[3]?.children?.size != 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        // Deliver the same set again — must not duplicate.
        router.handleEvent(Event("DOM.setChildNodes", mapOf("parentId" to 3, "nodes" to nodes), sessionId = session.sessionId))
        Thread.sleep(200)
        assertEquals(2, inspector.tree[3]?.children?.size)
        assertEquals(2, inspector.tree.allNodes.count { it.nodeId == 30 || it.nodeId == 31 })
    }

    @Test
    fun event_fromOtherSession_isIgnored() {
        bindLoaded()
        // A DOM event targeted at a different session must not mutate our tree
        router.handleEvent(Event(
            "DOM.setChildNodes",
            mapOf("parentId" to 3, "nodes" to listOf(mapOf("nodeId" to 30, "nodeType" to 1, "nodeName" to "P"))),
            sessionId = "other-session",
        ))
        // Give executor a chance to (wrongly) process — then assert no mutation.
        Thread.sleep(100)
        assertEquals(0, inspector.tree[3]?.children?.size ?: -1)
    }

    // ============ lifecycle ============

    @Test
    fun detach_clearsTreeAndSelection() {
        bindLoaded()
        inspector.selectNode(3)
        inspector.detach()
        assertEquals(DomState.INACTIVE, inspector.state())
        assertEquals(0, inspector.tree.size)
        assertNull(inspector.selectedNode())
        assertNull(inspector.session)
    }

    @Test
    fun onConnectionLost_clearsEverything() {
        bindLoaded()
        inspector.selectNode(3)
        inspector.onConnectionLost()
        assertEquals(DomState.INACTIVE, inspector.state())
        assertEquals(0, inspector.tree.size)
        assertNull(inspector.session)
    }

    @Test
    fun staleAsyncResponses_afterDetach_doNotMutateNewState() {
        bindLoaded()
        // Issue an expansion, then immediately detach, then respond.
        inspector.selectNode(3)
        transport.resetSentMessages()
        inspector.expandNode(3)
        val sentId = parseIdFor("DOM.requestChildNodes")
        inspector.detach()
        // Because generation changed, the response must be ignored.
        transport.deliverMessage("""{"id":$sentId,"sessionId":"session-1","result":{}}""")
        Thread.sleep(200)
        assertEquals(DomState.INACTIVE, inspector.state())
    }

    @Test
    fun protocolError_includingData_isReported_notSwallowed() {
        bindLoaded()
        inspector.selectNode(3)
        transport.resetSentMessages()
        var errorRef: Throwable? = null
        inspector.addListener(object : DomInspectorListener {
            override fun onStateChanged(state: DomState, error: Throwable?) {}
            override fun onDocumentLoaded(document: DomNode?) {}
            override fun onNodesChanged(changedNodeIds: Set<Int>) {}
            override fun onSelectionChanged(node: DomNode?) {}
            override fun onInspectionStateChanged(state: com.coppermux.cutools.core.dom.DomInspectionState) {}
            override fun onSearchStateChanged(state: com.coppermux.cutools.core.dom.DomSearchState) {}
            override fun onError(error: Throwable) { errorRef = error }
        })
        val result = AtomicReference<DomOperationResult<String>>()
        val latch = CountDownLatch(1)
        inspector.getOuterHTML { r -> result.set(r); latch.countDown() }
        val id = parseIdFor("DOM.getOuterHTML")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"Cannot inspect","data":"extra"}}""")
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(result.get() is DomOperationResult.Failure)
        val failure = (result.get() as DomOperationResult.Failure).error
        assertTrue(failure is ProtocolException)
        assertNotNull(errorRef)
    }

    @Test
    fun refresh_reloadsDocument() {
        bindLoaded()
        val before = inspector.tree.size
        transport.resetSentMessages()
        inspector.refresh()
        waitForGetDocument()
        assertEquals(DomState.LOADING, inspector.state())
        simulateDocumentLoaded()
        assertEquals(DomState.READY, inspector.state())
        assertEquals(before, inspector.tree.size)
    }

    // ================================================================
    // Phase 2B — inspection state (attributes, metadata, outer HTML)
    // ================================================================

    /** A richer document with attributes and a text child for search tests. */
    private fun simulateRichDocumentLoaded() {
        val getDocId = parseLastSentId()
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

    private fun bindRichLoaded() {
        inspector.bindSession(session)
        val enableId = parseIdFor("DOM.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitForGetDocument()
        simulateRichDocumentLoaded()
    }

    private fun awaitSearchComplete() {
        val deadline = System.currentTimeMillis() + 2000
        while (inspector.searchState.loading && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    @Test
    fun selectNode_populatesInspectionStateWithMetadataAndAttributes() {
        bindRichLoaded()
        val notif = AtomicReference<DomInspectionState?>()
        inspector.addListener(object : DomInspectorListener {
            override fun onStateChanged(state: DomState, error: Throwable?) {}
            override fun onDocumentLoaded(document: DomNode?) {}
            override fun onNodesChanged(changedNodeIds: Set<Int>) {}
            override fun onSelectionChanged(node: DomNode?) {}
            override fun onInspectionStateChanged(state: DomInspectionState) { notif.set(state) }
            override fun onSearchStateChanged(state: DomSearchState) {}
            override fun onError(error: Throwable) {}
        })
        inspector.selectNode(4)
        val state = inspector.inspectionState
        assertTrue(state.isSelected)
        assertEquals(4, state.node?.nodeId)
        assertEquals("H1", state.node?.nodeName)
        assertEquals("main", state.node?.attribute("id"))
        assertEquals("hero", state.node?.attribute("class"))
        assertEquals(1, state.node?.childNodeCount)
        // Structured attributes as a list
        assertEquals(2, state.node?.attributes?.size)
        assertEquals(listOf("id", "main", "class", "hero"), state.node!!.attributes.flatMap { listOf(it.name, it.value ?: "") })
        assertNotNull(notif.get())
    }

    @Test
    fun clearSelection_clearsInspectionState() {
        bindRichLoaded()
        inspector.selectNode(4)
        assertTrue(inspector.inspectionState.isSelected)
        inspector.clearSelection()
        assertFalse(inspector.inspectionState.isSelected)
        assertNull(inspector.inspectionState.node)
    }

    @Test
    fun inspectTextNode_reportsValue() {
        bindRichLoaded()
        inspector.selectNode(5)
        val n = inspector.selectedNode()!!
        assertTrue(n.isText)
        assertEquals("Hello World", n.nodeValue)
        assertTrue(inspector.inspectionState.isSelected)
    }

    @Test
    fun selectedNode_preservesEmptyAttributeValues() {
        bindRichLoaded()
        // Inject a node carrying an empty-valued (boolean) attribute via setChildNodes.
        router.handleEvent(Event(
            "DOM.setChildNodes",
            mapOf(
                "parentId" to 3,
                "nodes" to listOf(
                    mapOf("nodeId" to 40, "nodeType" to 1, "nodeName" to "INPUT", "attributes" to listOf("hidden", "", "type", "text")),
                ),
            ),
            sessionId = session.sessionId,
        ))
        val deadline = System.currentTimeMillis() + 2000
        while (inspector.tree[40] == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        inspector.selectNode(40)
        val state = inspector.inspectionState
        val attr = state.node?.attributes?.firstOrNull { it.name == "hidden" }
        assertNotNull(attr)
        assertEquals("", attr!!.value)
        assertEquals(2, state.node!!.attributes.size)
        assertTrue(state.isSelected)
    }

    @Test
    fun refreshOuterHtml_success_publishesHtml() {
        bindRichLoaded()
        inspector.selectNode(4)
        transport.resetSentMessages()
        inspector.refreshOuterHTML()
        assertTrue(transport.sentMessages.any { it.contains("DOM.getOuterHTML") })
        val id = parseIdFor("DOM.getOuterHTML")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"outerHTML":"<h1 id=\"main\" class=\"hero\">Hello World</h1>"}}""")
        val deadline = System.currentTimeMillis() + 2000
        while (inspector.inspectionState.outerHtml == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(inspector.inspectionState.hasOuterHtml)
        assertEquals("<h1 id=\"main\" class=\"hero\">Hello World</h1>", inspector.inspectionState.outerHtml)
        assertFalse(inspector.inspectionState.outerHtmlLoading)
        assertNull(inspector.inspectionState.outerHtmlError)
    }

    @Test
    fun refreshOuterHtml_emptyResult_isHandledWithoutHtml() {
        bindRichLoaded()
        inspector.selectNode(4)
        transport.resetSentMessages()
        inspector.refreshOuterHTML()
        val id = parseIdFor("DOM.getOuterHTML")
        // Result without outerHTML key → no html, no crash
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        val deadline = System.currentTimeMillis() + 2000
        while (inspector.inspectionState.outerHtmlLoading && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse(inspector.inspectionState.outerHtmlLoading)
        assertNull(inspector.inspectionState.outerHtml)
    }

    @Test
    fun refreshOuterHtml_protocolError_setsErrorState() {
        bindRichLoaded()
        inspector.selectNode(4)
        inspector.addListener(object : DomInspectorListener {
            override fun onStateChanged(state: DomState, error: Throwable?) {}
            override fun onDocumentLoaded(document: DomNode?) {}
            override fun onNodesChanged(changedNodeIds: Set<Int>) {}
            override fun onSelectionChanged(node: DomNode?) {}
            override fun onInspectionStateChanged(state: DomInspectionState) {}
            override fun onSearchStateChanged(state: DomSearchState) {}
            override fun onError(error: Throwable) {}
        })
        transport.resetSentMessages()
        inspector.refreshOuterHTML()
        val id = parseIdFor("DOM.getOuterHTML")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"Cannot inspect it"}}""")
        val deadline = System.currentTimeMillis() + 2000
        while (inspector.inspectionState.outerHtmlError == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertNotNull(inspector.inspectionState.outerHtmlError)
        assertNull(inspector.inspectionState.outerHtml)
        assertFalse(inspector.inspectionState.outerHtmlLoading)
    }

    @Test
    fun refreshOuterHtml_noSelection_clearsState() {
        bindLoaded()
        inspector.refreshOuterHTML()
        assertFalse(inspector.inspectionState.outerHtmlLoading)
        assertNull(inspector.inspectionState.outerHtml)
        assertNull(inspector.inspectionState.node)
    }

    @Test
    fun refreshOuterHtml_malformedResult_isHandledWithoutCrash() {
        bindRichLoaded()
        inspector.selectNode(4)
        transport.resetSentMessages()
        inspector.refreshOuterHTML()
        val id = parseIdFor("DOM.getOuterHTML")
        // A malformed result that is not an object with outerHTML → no html, no crash.
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":"not-an-object"}""")
        val deadline = System.currentTimeMillis() + 2000
        while (inspector.inspectionState.outerHtmlLoading && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse(inspector.inspectionState.outerHtmlLoading)
        assertNull(inspector.inspectionState.outerHtml)
        assertNull(inspector.inspectionState.outerHtmlError)
    }

    @Test
    fun refreshOuterHtml_staleResponse_isIgnoredAfterDetach() {
        bindRichLoaded()
        inspector.selectNode(4)
        transport.resetSentMessages()
        inspector.refreshOuterHTML()
        val id = parseIdFor("DOM.getOuterHTML")
        inspector.detach() // invalidates the generation before the response arrives
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{"outerHTML":"<h1>STALE</h1>"}}""")
        Thread.sleep(200)
        // Stale response must not populate state on the detached inspector
        assertNull(inspector.inspectionState.outerHtml)
        assertFalse(inspector.inspectionState.isSelected)
    }

    // ================================================================
    // Phase 2B — DOM search
    // ================================================================

    @Test
    fun searchByTag_findsElements() {
        bindRichLoaded()
        inspector.performSearch("H1")
        awaitSearchComplete()
        val s = inspector.searchState
        assertEquals(1, s.resultCount)
        assertEquals(4, s.activeResult?.nodeId)
        assertEquals(0, s.activeIndex)
    }

    @Test
    fun searchById_findsElement() {
        bindRichLoaded()
        inspector.performSearch("#box")
        awaitSearchComplete()
        assertEquals(1, inspector.searchState.resultCount)
        assertEquals(6, inspector.searchState.activeResult?.nodeId)
    }

    @Test
    fun searchByClass_findsElement() {
        bindRichLoaded()
        inspector.performSearch(".container")
        awaitSearchComplete()
        assertEquals(1, inspector.searchState.resultCount)
        assertEquals(3, inspector.searchState.activeResult?.nodeId)
    }

    @Test
    fun searchByAttribute_findsElement() {
        bindRichLoaded()
        inspector.performSearch("id")
        awaitSearchComplete()
        // id attribute present on nodes 3, 4, 6
        assertEquals(3, inspector.searchState.resultCount)
    }

    @Test
    fun searchByAttributeValue_findsElement() {
        bindRichLoaded()
        inspector.performSearch("id=box")
        awaitSearchComplete()
        assertEquals(1, inspector.searchState.resultCount)
        assertEquals(6, inspector.searchState.activeResult?.nodeId)
    }

    @Test
    fun searchByText_findsTextNode() {
        bindRichLoaded()
        inspector.performSearch("Hello World")
        awaitSearchComplete()
        assertTrue(inspector.searchState.resultCount >= 1)
        assertTrue(inspector.searchState.results.any { it.nodeId == 5 })
    }

    @Test
    fun search_noMatch_clearsActiveResult() {
        bindRichLoaded()
        inspector.performSearch("nonexistent-tag-xyz")
        awaitSearchComplete()
        val s = inspector.searchState
        assertEquals(0, s.resultCount)
        assertEquals(-1, s.activeIndex)
        assertFalse(s.hasResults)
    }

    @Test
    fun search_emptyQuery_clearsResults() {
        bindRichLoaded()
        inspector.performSearch("   ")
        awaitSearchComplete()
        val s = inspector.searchState
        assertEquals(0, s.resultCount)
        assertNull(s.error)
    }

    @Test
    fun search_nextNavigatesResults() {
        bindRichLoaded()
        // "id" attribute presence matches nodes 3, 4, 6 → 3 results
        inspector.performSearch("id")
        awaitSearchComplete()
        assertTrue(inspector.searchState.resultCount >= 3)
        val firstActive = inspector.searchState.activeIndex // 0
        inspector.searchNext()
        assertEquals(firstActive + 1, inspector.searchState.activeIndex)
        assertTrue(inspector.searchState.activeIndex < inspector.searchState.resultCount)
        // Wrap-around: after advancing past the end it returns to 0
        while (inspector.searchState.activeIndex != 0) inspector.searchNext()
        assertEquals(0, inspector.searchState.activeIndex)
    }

    @Test
    fun search_previousNavigatesResults() {
        bindRichLoaded()
        inspector.performSearch("id")
        awaitSearchComplete()
        val count = inspector.searchState.resultCount
        assertTrue(count >= 3)
        // Move to an interior index
        repeat(count - 1) { inspector.searchNext() }
        val active = inspector.searchState.activeIndex
        assertTrue(active > 0)
        inspector.searchPrevious()
        assertEquals((active - 1 + count) % count, inspector.searchState.activeIndex)
    }

    @Test
    fun search_selectResult_selectsNodeAndExpandsPath() {
        bindRichLoaded()
        inspector.performSearch("#box")
        awaitSearchComplete()
        inspector.selectSearchResult(0)
        assertEquals(6, inspector.selectedNode()?.nodeId)
        assertEquals(6, inspector.searchState.activeResult?.nodeId)
    }

    @Test
    fun search_next_selectsTheNode() {
        bindRichLoaded()
        inspector.performSearch("id")
        awaitSearchComplete()
        inspector.searchNext()
        val active = inspector.searchState.activeResult
        assertNotNull(active)
        // The active result is a real node and is selected
        assertTrue(inspector.tree.contains(active!!.nodeId))
        assertEquals(active.nodeId, inspector.selectedNode()?.nodeId)
    }

    @Test
    fun bindNewSession_clearsSearchAndInspectionState() {
        bindRichLoaded()
        inspector.selectNode(4)
        inspector.performSearch("div")
        awaitSearchComplete()
        assertTrue(inspector.searchState.hasResults)
        assertTrue(inspector.inspectionState.isSelected)
        // Simulate a target switch: bind to a new session
        inspector.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        assertFalse(inspector.searchState.hasResults)
        assertEquals("", inspector.searchState.query)
        assertFalse(inspector.inspectionState.isSelected)
        assertEquals(0, inspector.tree.size)
    }

    @Test
    fun connectionLoss_clearsSearchAndInspectionState() {
        bindRichLoaded()
        inspector.selectNode(4)
        inspector.performSearch("div")
        awaitSearchComplete()
        assertTrue(inspector.searchState.hasResults)
        assertTrue(inspector.inspectionState.isSelected)
        inspector.onConnectionLost()
        assertFalse(inspector.inspectionState.isSelected)
        assertNull(inspector.inspectionState.outerHtml)
        assertFalse(inspector.searchState.hasResults)
        assertEquals(DomState.INACTIVE, inspector.state())
    }

    @Test
    fun detach_clearsSearchAndInspectionState() {
        bindRichLoaded()
        inspector.selectNode(4)
        inspector.performSearch("div")
        awaitSearchComplete()
        inspector.detach()
        assertFalse(inspector.inspectionState.isSelected)
        assertFalse(inspector.searchState.hasResults)
    }
}
