package com.coppermux.cutools.core.css

import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomOperationResult
import com.coppermux.cutools.core.dom.DomState
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
 * Deterministic Phase 3A tests for [CssInspector].
 *
 * Drives the real CommandDispatcher + EventRouter through a fake in-memory
 * transport; never touches Chrome. Verifies CSS domain enablement, matched-style
 * retrieval on selection, loading/empty/failed/unsupported states, stale
 * response rejection, selection synchronization, stylesheet tracking, and
 * session isolation.
 */
class CssInspectorTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var router: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var domInspector: DomInspector
    private lateinit var cssInspector: CssInspector

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        router = CdpEventRouter()
        dispatcher = CommandDispatcher(transport, router, DispatcherConfig(defaultTimeoutMs = 2000))
        transport.connect("ws://test", dispatcher)
        domInspector = DomInspector(dispatcher, router)
        cssInspector = CssInspector(dispatcher, router, domInspector)
    }

    private val session = SessionInfo("session-1", "target-1", "ws://test")

    private fun parseIdFor(method: String): Long {
        val raw = transport.sentMessages.first { it.contains(method) }
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun nthIdFor(method: String, n: Int): Long {
        val msgs = transport.sentMessages.filter { it.contains(method) }
        assertTrue("Expected at least ${n + 1} occurrences of $method, got ${msgs.size}", msgs.size > n)
        val raw = msgs[n]
        return Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1].toLong()
    }

    private fun countSent(method: String): Int =
        transport.sentMessages.count { it.contains(method) }

    private fun waitFor(method: String, count: Int = 1) {
        val deadline = System.currentTimeMillis() + 3000
        while (countSent(method) < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("Expected $method to be sent", countSent(method) >= count)
    }

    private fun waitForState(phase: CssInspectionPhase) {
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.phase != phase && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(phase, cssInspector.inspectionState.phase)
    }

    private fun waitForCapability(status: CssCapabilityStatus) {
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.capability != status && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(status, cssInspector.capability)
    }

    /** Binds the DOM inspector and loads a small document into READY. */
    private fun bindLoaded() {
        domInspector.bindSession(session)
        val enableId = parseIdFor("DOM.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitFor("DOM.getDocument")
        val getDocId = parseIdFor("DOM.getDocument")
        transport.deliverMessage(
            """{"id":$getDocId,"sessionId":"session-1","result":{"root":{
               "nodeId":1,"nodeType":9,"nodeName":"#document","childNodeCount":1,
               "children":[{"nodeId":2,"nodeType":1,"nodeName":"HTML","childNodeCount":1,
                 "children":[{"nodeId":3,"nodeType":1,"nodeName":"BODY","childNodeCount":2}]}]}}}"""
        )
        awaitDomReady()
    }

    private fun awaitDomReady() {
        val deadline = System.currentTimeMillis() + 3000
        while (domInspector.state() != DomState.READY && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(DomState.READY, domInspector.state())
    }

    private fun enableCssSuccess() {
        waitFor("CSS.enable")
        val id = parseIdFor("CSS.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
        waitForCapability(CssCapabilityStatus.SUPPORTED)
    }

private fun respondStyles(stylesJson: String) {
        val id = nthIdFor("CSS.getMatchedStylesForNode", countSent("CSS.getMatchedStylesForNode") - 1)
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$stylesJson}""")
    }

    private fun respond(method: String, resultJson: String) {
        val id = nthIdFor(method, countSent(method) - 1)
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":$resultJson}""")
    }

    private fun respondError(method: String, code: Int, message: String) {
        val id = nthIdFor(method, countSent(method) - 1)
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":$code,"message":"$message"}}""")
    }

    /** Selects node 3 and answers the matched-style request (as Phase 3A tests do). */
    private fun bindLoadedEnabledAndInspectStyles() {
        bindLoaded()
        enableCssSuccess()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        respondStyles("{}")
        waitForState(CssInspectionPhase.EMPTY)
    }

    // =====================================================================
    // Enablement
    // =====================================================================

    @Test
    fun bind_enablesCssDomain() {
        bindLoaded()
        waitFor("CSS.enable")
        enableCssSuccess()
        assertEquals(CssCapabilityStatus.SUPPORTED, cssInspector.capability)
    }

    @Test
    fun cssEnableFailure_methodNotFound_marksUnsupported() {
        bindLoaded()
        waitFor("CSS.enable")
        val id = parseIdFor("CSS.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        waitForCapability(CssCapabilityStatus.UNSUPPORTED)
    }

    @Test
    fun cssEnableFailure_otherError_marksFailed() {
        bindLoaded()
        waitFor("CSS.enable")
        val id = parseIdFor("CSS.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"Something broke"}}""")
        waitForCapability(CssCapabilityStatus.FAILED)
    }

    // =====================================================================
    // Selection → style inspection
    // =====================================================================

    @Test
    fun selectNode_requestsMatchedStylesAndLoads() {
        bindLoaded()
        enableCssSuccess()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        respondStyles(
            """{"inlineStyle":{"cssProperties":[{"name":"color","value":"red"}]},
                "matchedCSSRules":[{"rule":{
                  "selectorList":{"text":"body","selectors":[{"text":"body"}]},
                  "origin":"regular","styleSheetId":"s1",
                  "style":{"range":{"startLine":1,"startColumn":0,"endLine":1,"endColumn":15},
                    "cssProperties":[{"name":"font-weight","value":"bold"}]}},
                  "matchingSelectors":[0]}]}"""
        )
        waitForState(CssInspectionPhase.LOADED)
        val state = cssInspector.inspectionState
        assertEquals(3, state.nodeId)
        assertEquals(1, state.matchedRuleCount)
        assertEquals("body", state.styles?.matchedRules?.first()?.selectorText)
        assertEquals("bold", state.styles?.matchedRules?.first()?.declarations?.first()?.value)
        assertEquals("red", state.styles?.inlineDeclarations?.first()?.value)
        assertNull(state.error)
    }

    @Test
    fun selectNode_noStyles_reportsEmpty() {
        bindLoaded()
        enableCssSuccess()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        respondStyles("{}")
        waitForState(CssInspectionPhase.EMPTY)
        assertEquals(3, cssInspector.inspectionState.nodeId)
    }

    @Test
    fun selectionCleared_resetsToIdle() {
        bindLoaded()
        enableCssSuccess()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        respondStyles("""{"inlineStyle":{"cssProperties":[{"name":"color","value":"red"}]}}""")
        waitForState(CssInspectionPhase.LOADED)
        domInspector.clearSelection()
        waitForState(CssInspectionPhase.IDLE)
        assertNull(cssInspector.inspectionState.nodeId)
    }

    @Test
    fun selectionChange_invalidatesPreviousNodeResponse() {
        bindLoaded()
        enableCssSuccess()
        // Select node 3 → request A.
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode", 1)
        val requestA = nthIdFor("CSS.getMatchedStylesForNode", 0)
        // Select node 3's child would be ideal, but the tree only has BODY(3);
        // select the same tree node via a fresh selection is a no-op for request.
        // Instead select nothing then re-select to force a new request cycle.
        domInspector.clearSelection()
        waitForState(CssInspectionPhase.IDLE)
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode", 2)
        val requestB = nthIdFor("CSS.getMatchedStylesForNode", 1)
        assertNotEquals("Each inspection must use its own command", requestA, requestB)

        // Deliver the OLD response for node 3 (still node 3, but from the older
        // generation) — it must be dropped because a newer request superseded it.
        transport.deliverMessage(
            """{"id":$requestA,"sessionId":"session-1","result":{"inlineStyle":{"cssProperties":[{"name":"color","value":"OLD"}]}}}"""
        )
        Thread.sleep(200)
        assertNotEquals("Stale response must not populate state", CssInspectionPhase.LOADED, cssInspector.inspectionState.phase)

        // Deliver the NEW response.
        transport.deliverMessage(
            """{"id":$requestB,"sessionId":"session-1","result":{"inlineStyle":{"cssProperties":[{"name":"color","value":"NEW"}]}}}"""
        )
        waitForState(CssInspectionPhase.LOADED)
        assertEquals("NEW", cssInspector.inspectionState.styles?.inlineDeclarations?.first()?.value)
    }

    @Test
    fun staleResponse_afterDetach_isDropped() {
        bindLoaded()
        enableCssSuccess()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        val id = nthIdFor("CSS.getMatchedStylesForNode", 0)
        domInspector.detach()
        // Detach resets CSS state to empty (IDLE).
        waitForState(CssInspectionPhase.IDLE)
        // Respond to the now-stale request — must not resurrect state.
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"inlineStyle":{"cssProperties":[{"name":"color","value":"stale"}]}}}"""
        )
        Thread.sleep(200)
        assertEquals(CssInspectionPhase.IDLE, cssInspector.inspectionState.phase)
        assertNull(cssInspector.inspectionState.styles)
    }

    // =====================================================================
    // Errors
    // =====================================================================

    @Test
    fun protocolError_reportsFailedState() {
        bindLoaded()
        enableCssSuccess()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        val id = nthIdFor("CSS.getMatchedStylesForNode", 0)
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32000,"message":"Node not found"}}""")
        waitForState(CssInspectionPhase.FAILED)
        assertNotNull(cssInspector.inspectionState.error)
    }

    @Test
    fun unsupportedCapability_reportsUnsupportedForSelection() {
        bindLoaded()
        waitFor("CSS.enable")
        val id = parseIdFor("CSS.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        waitForCapability(CssCapabilityStatus.UNSUPPORTED)
        domInspector.selectNode(3)
        waitForState(CssInspectionPhase.UNSUPPORTED)
        assertFalse("No command must be sent when the domain is unsupported", countSent("CSS.getMatchedStylesForNode") > 0)
    }

    // =====================================================================
    // Stylesheet tracking
    // =====================================================================

    @Test
    fun styleSheetAdded_tracksAndRemoved_untracks() {
        bindLoaded()
        enableCssSuccess()
        router.handleEvent(
            Event(
                "CSS.styleSheetAdded",
                mapOf("header" to mapOf(
                    "styleSheetId" to "s1",
                    "origin" to "regular",
                    "sourceURL" to "https://example.com/app.css",
                    "title" to "",
                    "disabled" to false,
                    "isInline" to false,
                    "isConstructed" to false,
                    "frameId" to "f1",
                )),
                sessionId = session.sessionId,
            )
        )
        val deadline = System.currentTimeMillis() + 2000
        while (cssInspector.inspectionState.stylesheets.none { it.styleSheetId == "s1" } && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(cssInspector.inspectionState.stylesheets.any { it.styleSheetId == "s1" })

        router.handleEvent(
            Event("CSS.styleSheetRemoved", mapOf("styleSheetId" to "s1"), sessionId = session.sessionId)
        )
        val deadline2 = System.currentTimeMillis() + 2000
        while (cssInspector.inspectionState.stylesheets.any { it.styleSheetId == "s1" } && System.currentTimeMillis() < deadline2) Thread.sleep(5)
        assertFalse(cssInspector.inspectionState.stylesheets.any { it.styleSheetId == "s1" })
    }

    @Test
    fun styleSheetEvent_fromOtherSession_isIgnored() {
        bindLoaded()
        enableCssSuccess()
        router.handleEvent(
            Event(
                "CSS.styleSheetAdded",
                mapOf("header" to mapOf(
                    "styleSheetId" to "s-other",
                    "origin" to "regular",
                    "sourceURL" to "x.css",
                    "title" to "",
                    "disabled" to false,
                    "isInline" to false,
                    "isConstructed" to false,
                )),
                sessionId = "other-session",
            )
        )
        Thread.sleep(200)
        assertFalse(cssInspector.inspectionState.stylesheets.any { it.styleSheetId == "s-other" })
    }

    @Test
    fun styleSheetChanged_reRequestsCurrentSelection() {
        bindLoaded()
        enableCssSuccess()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode", 1)
        respondStyles("""{"inlineStyle":{"cssProperties":[{"name":"color","value":"red"}]}}""")
        waitForState(CssInspectionPhase.LOADED)
        val before = countSent("CSS.getMatchedStylesForNode")

        router.handleEvent(
            Event("CSS.styleSheetChanged", mapOf("styleSheetId" to "s1"), sessionId = session.sessionId)
        )
        waitFor("CSS.getMatchedStylesForNode", before + 1)
    }

    @Test
    fun domRefresh_doesNotWipeTrackedStylesheets() {
        bindLoaded()
        enableCssSuccess()

        // A stylesheet is injected and tracked via its event.
        router.handleEvent(
            Event(
                "CSS.styleSheetAdded",
                mapOf("header" to mapOf(
                    "styleSheetId" to "sheet-lived",
                    "origin" to "regular",
                    "sourceURL" to "https://example.com/app.css",
                    "title" to "",
                    "disabled" to false,
                    "isInline" to false,
                    "isConstructed" to false,
                )),
                sessionId = session.sessionId,
            )
        )
        val addedDeadline = System.currentTimeMillis() + 2000
        while (cssInspector.inspectionState.stylesheets.none { it.styleSheetId == "sheet-lived" } && System.currentTimeMillis() < addedDeadline) Thread.sleep(5)
        assertTrue(cssInspector.inspectionState.stylesheets.any { it.styleSheetId == "sheet-lived" })

        // A DOM refresh (fresh READY) re-enables CSS but Chrome does not replay
        // already-known stylesheets — so tracked stylesheets must survive.
        transport.resetSentMessages()
        domInspector.refresh()
        waitFor("DOM.getDocument")
        val getDocId = parseIdFor("DOM.getDocument")
        transport.deliverMessage(
            """{"id":$getDocId,"sessionId":"session-1","result":{"root":{
               "nodeId":1,"nodeType":9,"nodeName":"#document","childNodeCount":1,
               "children":[{"nodeId":2,"nodeType":1,"nodeName":"HTML","childNodeCount":1,
                 "children":[{"nodeId":3,"nodeType":1,"nodeName":"BODY","childNodeCount":0}]}]}}}"""
        )
        awaitDomReady()

        // Answer the re-enable so no timeout can mark the capability failed.
        waitFor("CSS.enable", 1)
        val enableId2 = nthIdFor("CSS.enable", 0)
        transport.deliverMessage("""{"id":$enableId2,"sessionId":"session-1","result":{}}""")

        // The previously tracked stylesheet is still known after the refresh.
        val deadline = System.currentTimeMillis() + 2000
        while (cssInspector.inspectionState.phase != CssInspectionPhase.IDLE && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(
            "tracked stylesheet must survive a DOM refresh without event replay",
            cssInspector.inspectionState.stylesheets.any { it.styleSheetId == "sheet-lived" }
        )
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Test
    fun targetSwitch_clearsCssState_andDropsOldResponses() {
        bindLoaded()
        enableCssSuccess()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        val id = nthIdFor("CSS.getMatchedStylesForNode", 0)
        // Bind to a new session — CSS state must be cleared.
        domInspector.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        waitForState(CssInspectionPhase.IDLE)
        assertNull(cssInspector.inspectionState.nodeId)
        // Old-session response must be dropped.
        transport.deliverMessage(
            """{"id":$id,"sessionId":"session-1","result":{"inlineStyle":{"cssProperties":[{"name":"color","value":"stale"}]}}}"""
        )
        Thread.sleep(200)
        assertEquals(CssInspectionPhase.IDLE, cssInspector.inspectionState.phase)
    }

    @Test
    fun connectionLoss_clearsCssState() {
        bindLoaded()
        enableCssSuccess()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        respondStyles("""{"inlineStyle":{"cssProperties":[{"name":"color","value":"red"}]}}""")
        waitForState(CssInspectionPhase.LOADED)
        domInspector.onConnectionLost()
        waitForState(CssInspectionPhase.IDLE)
        assertNull(cssInspector.inspectionState.styles)
    }

    @Test
    fun inspectNode_explicitRequest_worksWithoutSelection() {
        bindLoaded()
        enableCssSuccess()
        cssInspector.inspectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        respondStyles("""{"inlineStyle":{"cssProperties":[{"name":"color","value":"blue"}]}}""")
        waitForState(CssInspectionPhase.LOADED)
        assertEquals(3, cssInspector.inspectionState.nodeId)
    }

    @Test
    fun refresh_reRequestsCurrentSelection() {
        bindLoaded()
        enableCssSuccess()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode", 1)
        respondStyles("""{"inlineStyle":{"cssProperties":[{"name":"color","value":"red"}]}}""")
        waitForState(CssInspectionPhase.LOADED)
        val before = countSent("CSS.getMatchedStylesForNode")
        cssInspector.refresh()
        waitFor("CSS.getMatchedStylesForNode", before + 1)
    }

    // =====================================================================
    // Phase 3B — computed styles
    // =====================================================================

    @Test
    fun selection_fetchesComputedStyle_loads() {
        bindLoaded()
        enableCssSuccess()
        domInspector.selectNode(3)
        waitFor("CSS.getComputedStyleForNode")
        respond(
            "CSS.getComputedStyleForNode",
            """{"computedStyle":[{"name":"color","value":"rgb(0, 128, 0)"},{"name":"width","value":"300px"}]}""",
        )
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.isComputedStyleLoading && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssInspectionPhase.LOADED, cssInspector.inspectionState.computedStylePhase)
        assertEquals(2, cssInspector.inspectionState.computedStyle?.size)
        assertEquals("rgb(0, 128, 0)", cssInspector.inspectionState.computedStyle?.first { it.name == "color" }?.value)
        assertNull(cssInspector.inspectionState.computedStyleError)
    }

    @Test
    fun computedStyle_emptyResponse_reportsEmpty() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getComputedStyleForNode")
        respond("CSS.getComputedStyleForNode", """{}""")
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.computedStylePhase == CssInspectionPhase.LOADING && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssInspectionPhase.EMPTY, cssInspector.inspectionState.computedStylePhase)
    }

    @Test
    fun computedStyle_protocolError_reportsFailed() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getComputedStyleForNode")
        respondError("CSS.getComputedStyleForNode", -32000, "Node not found")
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.computedStylePhase == CssInspectionPhase.LOADING && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssInspectionPhase.FAILED, cssInspector.inspectionState.computedStylePhase)
        assertNotNull(cssInspector.inspectionState.computedStyleError)
    }

    // =====================================================================
    // Phase 3B — cascade layers
    // =====================================================================

    @Test
    fun selection_fetchesLayers_loads() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getLayersForNode")
        respond(
            "CSS.getLayersForNode",
            """{"rootLayer":{"name":"implicit outer layer","order":2,
                "subLayers":[{"name":"base","order":0},{"name":"theme","order":1}]}}""",
        )
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.layerPhase == CssInspectionPhase.LOADING && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssInspectionPhase.LOADED, cssInspector.inspectionState.layerPhase)
        assertEquals("implicit outer layer", cssInspector.inspectionState.rootLayer?.name)
        assertEquals(2, cssInspector.inspectionState.rootLayer?.subLayers?.size)
    }

    @Test
    fun layers_methodNotFound_reportsUnsupported() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getLayersForNode")
        respondError("CSS.getLayersForNode", -32601, "Method not found")
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.layerPhase == CssInspectionPhase.LOADING && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssInspectionPhase.UNSUPPORTED, cssInspector.inspectionState.layerPhase)
    }

    // =====================================================================
    // Phase 3B — box model
    // =====================================================================

    @Test
    fun selection_fetchesBoxModel_loads() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getBoxModel")
        respond(
            "DOM.getBoxModel",
            """{"model":{"content":[0,0,100,0,100,50,0,50],"padding":[0,0,100,0,100,50,0,50],"border":[0,0,100,0,100,50,0,50],"margin":[0,0,100,0,100,50,0,50],"width":100,"height":50}}""",
        )
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.boxModelPhase == CssInspectionPhase.LOADING && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssInspectionPhase.LOADED, cssInspector.inspectionState.boxModelPhase)
        assertEquals(100, cssInspector.inspectionState.boxModel?.width)
        assertEquals(50, cssInspector.inspectionState.boxModel?.height)
        assertNull(cssInspector.inspectionState.boxModelError)
    }

    @Test
    fun boxModel_protocolError_reportsFailed() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getBoxModel")
        respondError("DOM.getBoxModel", -32000, "Cannot get box model")
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.boxModelPhase == CssInspectionPhase.LOADING && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssInspectionPhase.FAILED, cssInspector.inspectionState.boxModelPhase)
        assertNotNull(cssInspector.inspectionState.boxModelError)
    }

    @Test
    fun boxModel_methodNotFound_reportsUnsupported() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getBoxModel")
        respondError("DOM.getBoxModel", -32601, "Method not found")
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.boxModelPhase == CssInspectionPhase.LOADING && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssInspectionPhase.UNSUPPORTED, cssInspector.inspectionState.boxModelPhase)
    }

    // =====================================================================
    // Phase 3B — lifecycle / stale handling across sub-states
    // =====================================================================

    @Test
    fun computedStyle_staleResponse_afterSelectionChange_isDropped() {
        bindLoadedAndEnable()
        // First inspection request.
        domInspector.selectNode(3)
        waitFor("CSS.getComputedStyleForNode", 1)
        val first = nthIdFor("CSS.getComputedStyleForNode", 0)
        // Second inspection (re-selection) supersedes the first.
        domInspector.clearSelection()
        waitForState(CssInspectionPhase.IDLE)
        domInspector.selectNode(3)
        waitFor("CSS.getComputedStyleForNode", 2)
        // Deliver the OLD response — it must be dropped (newer generation).
        transport.deliverMessage(
            """{"id":$first,"sessionId":"session-1","result":{"computedStyle":[{"name":"color","value":"OLD"}]}}"""
        )
        Thread.sleep(200)
        assertNotEquals(CssInspectionPhase.LOADED, cssInspector.inspectionState.computedStylePhase)
        // Deliver the NEW response.
        respond(
            "CSS.getComputedStyleForNode",
            """{"computedStyle":[{"name":"color","value":"NEW"}]}""",
        )
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.computedStylePhase == CssInspectionPhase.LOADING && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssInspectionPhase.LOADED, cssInspector.inspectionState.computedStylePhase)
        assertEquals(
            "NEW",
            cssInspector.inspectionState.computedStyle?.first { it.name == "color" }?.value
        )
    }

    @Test
    fun selectionChange_resetsAllSubStates() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getComputedStyleForNode")
        respond("CSS.getComputedStyleForNode", """{"computedStyle":[{"name":"color","value":"red"}]}""")
        waitFor("DOM.getBoxModel")
        respond("DOM.getBoxModel", """{"model":{"width":10,"height":10,"content":[0,0,10,0,10,10,0,10],"padding":[0,0,10,0,10,10,0,10],"border":[0,0,10,0,10,10,0,10],"margin":[0,0,10,0,10,10,0,10]}}""")
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.computedStylePhase != CssInspectionPhase.LOADED && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssInspectionPhase.LOADED, cssInspector.inspectionState.computedStylePhase)

        // Selection cleared invalidates computed + box + layer state.
        domInspector.clearSelection()
        waitForState(CssInspectionPhase.IDLE)
        assertEquals(CssInspectionPhase.IDLE, cssInspector.inspectionState.computedStylePhase)
        assertEquals(CssInspectionPhase.IDLE, cssInspector.inspectionState.boxModelPhase)
        assertNull(cssInspector.inspectionState.computedStyle)
        assertNull(cssInspector.inspectionState.boxModel)
    }

    @Test
    fun targetSwitch_clearsComputedAndBoxState() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getComputedStyleForNode")
        respond("CSS.getComputedStyleForNode", """{"computedStyle":[{"name":"color","value":"red"}]}""")
        waitFor("DOM.getBoxModel")
        respond("DOM.getBoxModel", """{"model":{"width":10,"height":10,"content":[0,0,10,0,10,10,0,10],"padding":[0,0,10,0,10,10,0,10],"border":[0,0,10,0,10,10,0,10],"margin":[0,0,10,0,10,10,0,10]}}""")
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.computedStylePhase != CssInspectionPhase.LOADED && System.currentTimeMillis() < deadline) Thread.sleep(5)

        domInspector.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        waitForState(CssInspectionPhase.IDLE)
        assertEquals(CssInspectionPhase.IDLE, cssInspector.inspectionState.computedStylePhase)
        assertEquals(CssInspectionPhase.IDLE, cssInspector.inspectionState.boxModelPhase)
        assertEquals(CssInspectionPhase.IDLE, cssInspector.inspectionState.layerPhase)
        assertNull(cssInspector.inspectionState.computedStyle)
        assertNull(cssInspector.inspectionState.boxModel)
    }

    @Test
    fun disconnect_clearsComputedAndBoxState_noStaleApplication() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getComputedStyleForNode")
        val first = nthIdFor("CSS.getComputedStyleForNode", 0)
        domInspector.onConnectionLost()
        waitForState(CssInspectionPhase.IDLE)
        // Stale computed response after connection loss must not resurrect state.
        transport.deliverMessage(
            """{"id":$first,"sessionId":"session-1","result":{"computedStyle":[{"name":"color","value":"stale"}]}}"""
        )
        Thread.sleep(200)
        assertEquals(CssInspectionPhase.IDLE, cssInspector.inspectionState.computedStylePhase)
        assertNull(cssInspector.inspectionState.computedStyle)
        assertEquals(CssInspectionPhase.IDLE, cssInspector.inspectionState.boxModelPhase)
    }

    private fun bindLoadedAndEnable() {
        bindLoaded()
        enableCssSuccess()
    }

    // =====================================================================
    // Phase 3C — pseudo-class forcing
    // =====================================================================

    private fun awaitPseudoPhase(phase: CssPseudoStatePhase) {
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.pseudoState.phase != phase && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(phase, cssInspector.inspectionState.pseudoState.phase)
    }

    private fun deliverPseudoSuccess() {
        val id = nthIdFor("CSS.forcePseudoState", countSent("CSS.forcePseudoState") - 1)
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
    }

    @Test
    fun forcePseudoClass_sendsCommand_andConfirmsOnSuccess() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        val latch = CountDownLatch(1)
        val res = AtomicReference<DomOperationResult<Unit>>()
        cssInspector.setPseudoClass(3, CssPseudoClass.HOVER, true) { r -> res.set(r); latch.countDown() }
        waitFor("CSS.forcePseudoState")
        val sent = transport.sentMessages.first { it.contains("CSS.forcePseudoState") }
        assertTrue(sent.contains("\"nodeId\":3"))
        assertTrue(sent.contains("\"hover\""))
        assertEquals(CssPseudoStatePhase.APPLYING, cssInspector.inspectionState.pseudoState.phase)
        deliverPseudoSuccess()
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertTrue(res.get() is DomOperationResult.Success)
        awaitPseudoPhase(CssPseudoStatePhase.APPLIED)
        assertTrue(cssInspector.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
        assertEquals(3, cssInspector.inspectionState.pseudoState.nodeId)
    }

    @Test
    fun removePseudoClass_sendsReducedSet() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setPseudoClass(3, CssPseudoClass.HOVER, true) { }
        waitFor("CSS.forcePseudoState", 1)
        deliverPseudoSuccess()
        awaitPseudoPhase(CssPseudoStatePhase.APPLIED)

        transport.resetSentMessages()
        cssInspector.setPseudoClass(3, CssPseudoClass.HOVER, false) { }
        waitFor("CSS.forcePseudoState", 1)
        val sent = transport.sentMessages.first { it.contains("CSS.forcePseudoState") }
        assertEquals(CssPseudoStatePhase.REMOVING, cssInspector.inspectionState.pseudoState.phase)
        assertFalse(sent.contains("\"hover\""))
        deliverPseudoSuccess()
        awaitPseudoPhase(CssPseudoStatePhase.APPLIED)
        assertFalse(cssInspector.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
    }

    @Test
    fun concurrentPseudoOperation_rejected() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setPseudoClass(3, CssPseudoClass.HOVER, true) { }
        waitFor("CSS.forcePseudoState")
        // Second attempt while pending must be rejected.
        val latch = CountDownLatch(1)
        val res = AtomicReference<DomOperationResult<Unit>>()
        cssInspector.setPseudoClass(3, CssPseudoClass.FOCUS, true) { r -> res.set(r); latch.countDown() }
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertTrue(res.get() is DomOperationResult.Failure)
        // Complete the first.
        deliverPseudoSuccess()
        awaitPseudoPhase(CssPseudoStatePhase.APPLIED)
        assertTrue(cssInspector.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
    }

    @Test
    fun pseudoOperation_protocolError_preservesConfirmedSet() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setPseudoClass(3, CssPseudoClass.HOVER, true) { }
        waitFor("CSS.forcePseudoState")
        respondError("CSS.forcePseudoState", -32000, "Cannot force")
        awaitPseudoPhase(CssPseudoStatePhase.FAILED)
        assertNotNull(cssInspector.inspectionState.pseudoState.error)
        assertFalse("rejected operation must not appear active", cssInspector.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
    }

    @Test
    fun pseudoOperation_methodNotFound_reportsUnsupported() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setPseudoClass(3, CssPseudoClass.HOVER, true) { }
        waitFor("CSS.forcePseudoState")
        respondError("CSS.forcePseudoState", -32601, "Method not found")
        awaitPseudoPhase(CssPseudoStatePhase.UNSUPPORTED)
    }

    @Test
    fun pseudoOperation_whenCssUnsupported_isRejected() {
        bindLoaded()
        waitFor("CSS.enable")
        val id = parseIdFor("CSS.enable")
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","error":{"code":-32601,"message":"Method not found"}}""")
        waitForCapability(CssCapabilityStatus.UNSUPPORTED)
        domInspector.selectNode(3)
        val latch = CountDownLatch(1)
        val res = AtomicReference<DomOperationResult<Unit>>()
        cssInspector.setPseudoClass(3, CssPseudoClass.HOVER, true) { r -> res.set(r); latch.countDown() }
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertTrue(res.get() is DomOperationResult.Failure)
        assertEquals(CssPseudoStatePhase.UNSUPPORTED, cssInspector.inspectionState.pseudoState.phase)
        assertFalse("no command must be sent when CSS is unsupported", countSent("CSS.forcePseudoState") > 0)
    }

    @Test
    fun selectionChange_resetsPseudoState_andClearsOldNodeInChrome() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setPseudoClass(3, CssPseudoClass.HOVER, true) { }
        waitFor("CSS.forcePseudoState", 1)
        deliverPseudoSuccess()
        awaitPseudoPhase(CssPseudoStatePhase.APPLIED)
        assertTrue(cssInspector.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))

        // Selecting a different node must reset pseudo-state and clear the old
        // node's forced pseudo-classes in Chrome (empty forcedPseudoClasses).
        domInspector.selectNode(2)
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER) && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse("pseudo-state must not transfer to the new node", cssInspector.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
        val clearSent = transport.sentMessages.any {
            it.contains("CSS.forcePseudoState") && it.contains("\"nodeId\":3") && it.contains("forcedPseudoClasses\":[]")
        }
        assertTrue("old node forced state must be cleared in Chrome", clearSent)
    }

    @Test
    fun selectionChange_preservesPseudoStateForSameNodeRefresh() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setPseudoClass(3, CssPseudoClass.FOCUS, true) { }
        waitFor("CSS.forcePseudoState")
        deliverPseudoSuccess()
        awaitPseudoPhase(CssPseudoStatePhase.APPLIED)

        transport.resetSentMessages()
        cssInspector.refresh()
        waitFor("CSS.getMatchedStylesForNode")
        assertTrue("refresh must preserve confirmed pseudo-state", cssInspector.inspectionState.pseudoState.isForced(CssPseudoClass.FOCUS))
    }

    @Test
    fun pseudoState_staleResponse_afterSelectionChange_isDropped() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setPseudoClass(3, CssPseudoClass.HOVER, true) { }
        waitFor("CSS.forcePseudoState", 1)
        val first = nthIdFor("CSS.forcePseudoState", 0)
        // Switch selection before the response arrives.
        domInspector.selectNode(2)
        // Deliver the stale success — must not mark hover forced on the new selection.
        transport.deliverMessage("""{"id":$first,"sessionId":"session-1","result":{}}""")
        Thread.sleep(200)
        assertFalse(cssInspector.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
        assertNotEquals(CssPseudoStatePhase.APPLIED, cssInspector.inspectionState.pseudoState.phase)
    }

    @Test
    fun targetSwitch_clearsPseudoState() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setPseudoClass(3, CssPseudoClass.HOVER, true) { }
        waitFor("CSS.forcePseudoState")
        deliverPseudoSuccess()
        awaitPseudoPhase(CssPseudoStatePhase.APPLIED)

        domInspector.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.pseudoState.phase != CssPseudoStatePhase.IDLE && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssPseudoStatePhase.IDLE, cssInspector.inspectionState.pseudoState.phase)
        assertFalse(cssInspector.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
    }

    @Test
    fun disconnect_clearsPseudoState_noStaleApplication() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setPseudoClass(3, CssPseudoClass.HOVER, true) { }
        waitFor("CSS.forcePseudoState", 1)
        val first = nthIdFor("CSS.forcePseudoState", 0)
        domInspector.onConnectionLost()
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.pseudoState.phase != CssPseudoStatePhase.IDLE && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssPseudoStatePhase.IDLE, cssInspector.inspectionState.pseudoState.phase)
        // Stale response must not resurrect pseudo-state.
        transport.deliverMessage("""{"id":$first,"sessionId":"session-1","result":{}}""")
        Thread.sleep(200)
        assertFalse(cssInspector.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
    }

    // =====================================================================
    // Phase 3D — layout / container queries
    // =====================================================================

    private fun awaitLayoutPhase(phase: CssInspectionPhase) {
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.layoutPhase != phase && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(phase, cssInspector.inspectionState.layoutPhase)
    }

    private fun awaitContainerPhase(phase: CssInspectionPhase) {
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.containerPhase != phase && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(phase, cssInspector.inspectionState.containerPhase)
    }

    @Test
    fun selection_fetchesLayout_loads() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getContentQuads")
        respond("DOM.getContentQuads", """{"quads":[[8,8,100,8,100,26,8,26]]}""")
        waitFor("DOM.getRelayoutBoundary")
        respond("DOM.getRelayoutBoundary", """{"nodeId":2}""")
        awaitLayoutPhase(CssInspectionPhase.LOADED)
        val info = cssInspector.inspectionState.layoutInfo
        assertNotNull(info)
        assertEquals(1, info!!.contentQuads.size)
        assertEquals(8.0, info.contentQuads[0].left!!, 0.001)
        // The relayout-boundary response may arrive after the content-quads one.
        val bd = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.layoutInfo?.relayoutBoundaryNodeId == null && System.currentTimeMillis() < bd) Thread.sleep(5)
        assertEquals(2, cssInspector.inspectionState.layoutInfo?.relayoutBoundaryNodeId)
        assertNull(cssInspector.inspectionState.layoutError)
    }

    @Test
    fun layout_emptyQuads_reportsEmpty() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getContentQuads")
        respond("DOM.getContentQuads", """{"quads":[]}""")
        awaitLayoutPhase(CssInspectionPhase.EMPTY)
    }

    @Test
    fun layout_protocolError_reportsFailed() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getContentQuads")
        respondError("DOM.getContentQuads", -32000, "Node not found")
        awaitLayoutPhase(CssInspectionPhase.FAILED)
        assertNotNull(cssInspector.inspectionState.layoutError)
    }

    @Test
    fun layout_methodNotFound_reportsUnsupported() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getContentQuads")
        respondError("DOM.getContentQuads", -32601, "Method not found")
        awaitLayoutPhase(CssInspectionPhase.UNSUPPORTED)
    }

    @Test
    fun container_detected_forNode_loads() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getContainerForNode")
        respond("DOM.getContainerForNode", """{"nodeId":2}""")
        awaitContainerPhase(CssInspectionPhase.LOADED)
        assertEquals(2, cssInspector.inspectionState.containerInfo?.containerNodeId)
        assertNull(cssInspector.inspectionState.containerError)
    }

    @Test
    fun container_none_reportsEmpty() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getContainerForNode")
        respond("DOM.getContainerForNode", """{}""")
        awaitContainerPhase(CssInspectionPhase.EMPTY)
        assertNull(cssInspector.inspectionState.containerInfo?.containerNodeId)
    }

    @Test
    fun container_methodNotFound_reportsUnsupported() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getContainerForNode")
        respondError("DOM.getContainerForNode", -32601, "Method not found")
        awaitContainerPhase(CssInspectionPhase.UNSUPPORTED)
    }

    @Test
    fun gridAndFlexCapabilities_areHonestlyUnsupported() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.nodeId == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssCapabilityStatus.UNSUPPORTED, cssInspector.inspectionState.gridCapability)
        assertEquals(CssCapabilityStatus.UNSUPPORTED, cssInspector.inspectionState.flexCapability)
        assertFalse(cssInspector.inspectionState.isGridSupported)
        assertFalse(cssInspector.inspectionState.isFlexSupported)
    }

    @Test
    fun selectionChange_resetsLayoutAndContainer() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getContentQuads")
        respond("DOM.getContentQuads", """{"quads":[[8,8,100,8,100,26,8,26]]}""")
        waitFor("DOM.getContainerForNode")
        respond("DOM.getContainerForNode", """{"nodeId":2}""")
        awaitLayoutPhase(CssInspectionPhase.LOADED)
        awaitContainerPhase(CssInspectionPhase.LOADED)

        domInspector.clearSelection()
        waitForState(CssInspectionPhase.IDLE)
        assertEquals(CssInspectionPhase.IDLE, cssInspector.inspectionState.layoutPhase)
        assertEquals(CssInspectionPhase.IDLE, cssInspector.inspectionState.containerPhase)
        assertNull(cssInspector.inspectionState.layoutInfo)
        assertNull(cssInspector.inspectionState.containerInfo)
    }

    @Test
    fun layout_staleResponse_afterSelectionChange_isDropped() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("DOM.getContentQuads", 1)
        val first = nthIdFor("DOM.getContentQuads", 0)
        domInspector.selectNode(2)
        transport.deliverMessage(
            """{"id":$first,"sessionId":"session-1","result":{"quads":[[1,1,2,1,2,2,1,2]]}}"""
        )
        Thread.sleep(200)
        assertNotEquals(CssInspectionPhase.LOADED, cssInspector.inspectionState.layoutPhase)
    }

    // =====================================================================
    // Phase 3E — layout overlays
    // =====================================================================

    private fun awaitOverlayPhase(phase: CssOverlayPhase) {
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.overlayState.phase != phase && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(phase, cssInspector.inspectionState.overlayState.phase)
    }

    private fun deliverOverlaySuccess(method: String) {
        val id = nthIdFor(method, countSent(method) - 1)
        transport.deliverMessage("""{"id":$id,"sessionId":"session-1","result":{}}""")
    }

    @Test
    fun setOverlay_enablesDomain_andShowsGrid() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        val latch = CountDownLatch(1)
        val res = AtomicReference<DomOperationResult<Unit>>()
        cssInspector.setOverlay(CssOverlayType.GRID, 3, true) { r -> res.set(r); latch.countDown() }
        waitFor("Overlay.enable")
        val enableId = parseIdFor("Overlay.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitFor("Overlay.setShowGridOverlays")
        val sent = transport.sentMessages.first { it.contains("Overlay.setShowGridOverlays") }
        assertTrue(sent.contains("\"nodeId\":3"))
        assertEquals(CssOverlayPhase.REQUESTED, cssInspector.inspectionState.overlayState.phase)
        deliverOverlaySuccess("Overlay.setShowGridOverlays")
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertTrue(res.get() is DomOperationResult.Success)
        awaitOverlayPhase(CssOverlayPhase.ACTIVE)
        assertEquals(CssOverlayType.GRID, cssInspector.inspectionState.overlayState.type)
    }

    @Test
    fun disableOverlay_sendsEmptyArray() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setOverlay(CssOverlayType.FLEX, 3, true) { }
        waitFor("Overlay.enable")
        val enableId = parseIdFor("Overlay.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitFor("Overlay.setShowFlexOverlays")
        deliverOverlaySuccess("Overlay.setShowFlexOverlays")
        awaitOverlayPhase(CssOverlayPhase.ACTIVE)

        transport.resetSentMessages()
        cssInspector.setOverlay(CssOverlayType.FLEX, 3, false) { }
        waitFor("Overlay.setShowFlexOverlays", 1)
        val sent = transport.sentMessages.first { it.contains("Overlay.setShowFlexOverlays") }
        assertTrue("disable must send an empty config array", sent.contains("flexNodeHighlightConfigs\":[]"))
        deliverOverlaySuccess("Overlay.setShowFlexOverlays")
        awaitOverlayPhase(CssOverlayPhase.DISABLED)
        assertFalse(cssInspector.inspectionState.overlayState.isActive)
    }

    @Test
    fun overlay_protocolError_reportsFailed() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setOverlay(CssOverlayType.CONTAINER_QUERY, 3, true) { }
        waitFor("Overlay.enable")
        val enableId = parseIdFor("Overlay.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitFor("Overlay.setShowContainerQueryOverlays")
        respondError("Overlay.setShowContainerQueryOverlays", -32000, "Cannot show overlay")
        awaitOverlayPhase(CssOverlayPhase.FAILED)
        assertNotNull(cssInspector.inspectionState.overlayState.error)
        assertFalse(cssInspector.inspectionState.overlayState.isActive)
    }

    @Test
    fun overlay_methodNotFound_reportsUnsupported() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setOverlay(CssOverlayType.GRID, 3, true) { }
        waitFor("Overlay.enable")
        respondError("Overlay.enable", -32601, "Method not found")
        awaitOverlayPhase(CssOverlayPhase.UNSUPPORTED)
        assertFalse("no overlay command when domain unsupported", countSent("Overlay.setShowGridOverlays") > 0)
    }

    @Test
    fun overlay_concurrentOperation_rejected() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setOverlay(CssOverlayType.GRID, 3, true) { }
        waitFor("Overlay.enable")
        val enableId = parseIdFor("Overlay.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitFor("Overlay.setShowGridOverlays")
        val latch = CountDownLatch(1)
        val res = AtomicReference<DomOperationResult<Unit>>()
        cssInspector.setOverlay(CssOverlayType.GRID, 3, false) { r -> res.set(r); latch.countDown() }
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertTrue(res.get() is DomOperationResult.Failure)
        deliverOverlaySuccess("Overlay.setShowGridOverlays")
        awaitOverlayPhase(CssOverlayPhase.ACTIVE)
    }

    @Test
    fun overlaySelectionChange_resetsAndClearsOldNode() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setOverlay(CssOverlayType.GRID, 3, true) { }
        waitFor("Overlay.enable")
        val enableId = parseIdFor("Overlay.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitFor("Overlay.setShowGridOverlays")
        deliverOverlaySuccess("Overlay.setShowGridOverlays")
        awaitOverlayPhase(CssOverlayPhase.ACTIVE)

        domInspector.selectNode(2)
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.overlayState.isActive && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse("overlay must not transfer to the new node", cssInspector.inspectionState.overlayState.isActive)
        val clearSent = transport.sentMessages.any {
            it.contains("Overlay.setShowGridOverlays") && it.contains("gridNodeHighlightConfigs\":[]")
        }
        assertTrue("old node overlay must be cleared in Chrome", clearSent)
    }

    @Test
    fun overlayStaleResponse_afterSelectionChange_isDropped() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setOverlay(CssOverlayType.GRID, 3, true) { }
        waitFor("Overlay.enable")
        val enableId = parseIdFor("Overlay.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitFor("Overlay.setShowGridOverlays")
        val first = nthIdFor("Overlay.setShowGridOverlays", 0)
        domInspector.selectNode(2)
        transport.deliverMessage("""{"id":$first,"sessionId":"session-1","result":{}}""")
        Thread.sleep(200)
        assertFalse(cssInspector.inspectionState.overlayState.isActive)
    }

    @Test
    fun targetSwitch_clearsOverlayState() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setOverlay(CssOverlayType.GRID, 3, true) { }
        waitFor("Overlay.enable")
        val enableId = parseIdFor("Overlay.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitFor("Overlay.setShowGridOverlays")
        deliverOverlaySuccess("Overlay.setShowGridOverlays")
        awaitOverlayPhase(CssOverlayPhase.ACTIVE)

        domInspector.bindSession(SessionInfo("session-2", "target-2", "ws://test"))
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.overlayState.phase != CssOverlayPhase.DISABLED && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssOverlayPhase.DISABLED, cssInspector.inspectionState.overlayState.phase)
    }

    @Test
    fun disconnect_clearsOverlayState_noStaleApplication() {
        bindLoadedAndEnable()
        domInspector.selectNode(3)
        waitFor("CSS.getMatchedStylesForNode")
        cssInspector.setOverlay(CssOverlayType.GRID, 3, true) { }
        waitFor("Overlay.enable")
        val enableId = parseIdFor("Overlay.enable")
        transport.deliverMessage("""{"id":$enableId,"sessionId":"session-1","result":{}}""")
        waitFor("Overlay.setShowGridOverlays")
        val first = nthIdFor("Overlay.setShowGridOverlays", 0)
        domInspector.onConnectionLost()
        val deadline = System.currentTimeMillis() + 3000
        while (cssInspector.inspectionState.overlayState.phase != CssOverlayPhase.DISABLED && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(CssOverlayPhase.DISABLED, cssInspector.inspectionState.overlayState.phase)
        transport.deliverMessage("""{"id":$first,"sessionId":"session-1","result":{}}""")
        Thread.sleep(200)
        assertFalse(cssInspector.inspectionState.overlayState.isActive)
    }
}