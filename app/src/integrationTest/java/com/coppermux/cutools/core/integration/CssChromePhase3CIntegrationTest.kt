package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.css.CssCapabilityStatus
import com.coppermux.cutools.core.css.CssInspectionPhase
import com.coppermux.cutools.core.css.CssInspector
import com.coppermux.cutools.core.css.CssPseudoClass
import com.coppermux.cutools.core.css.CssPseudoStatePhase
import com.coppermux.cutools.core.dom.DomInspector
import com.coppermux.cutools.core.dom.DomNode
import com.coppermux.cutools.core.dom.DomState
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetInfo
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.WebSocketCdpTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 3C — Pseudo-States and Live CSS Synchronization real-Chrome validation.
 *
 * Proves against REAL Chrome:
 *  - Applying :hover / :active / :focus via `CSS.forcePseudoState`
 *  - The full forced set replaces prior state (hover+active)
 *  - Removing pseudo-states reverts the page
 *  - CSS inspection (computed styles) refreshes to reflect the forced state
 *  - Matched rules reflect the forced pseudo-class
 *  - Selection changes reset pseudo-state (never transferred) and clear the
 *    previous node's forced state in Chrome
 *
 * Uses genuine CDP commands through the Phase 1 foundation (no second
 * WebSocket). Skips when Chrome is not reachable at http://127.0.0.1:9224.
 */
class CssChromePhase3CIntegrationTest {

    private lateinit var browserWsUrl: String

    @Before
    fun discoverChrome() {
        browserWsUrl = try {
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url("http://127.0.0.1:9224/json/version").build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = com.coppermux.cutools.core.model.Protocol.moshi
                    .adapter(Map::class.java).fromJson(body)
                (json?.get("webSocketDebuggerUrl") as? String).orEmpty()
            }
        } catch (e: Exception) {
            ""
        }
        assumeTrue("Real Chrome not reachable at http://127.0.0.1:9224 — skipping", browserWsUrl.isNotBlank())
    }

    private fun newManager(): ConnectionManager =
        ConnectionManager.create(WebSocketCdpTransport(), DispatcherConfig(defaultTimeoutMs = 5000))

    private fun await(latch: CountDownLatch, what: String) {
        assertTrue("Timed out waiting for: $what", latch.await(20, TimeUnit.SECONDS))
    }

    private fun connectAttachPage(cm: ConnectionManager): Pair<SessionInfo, DomInspector> {
        val connected = CountDownLatch(1)
        cm.connect(url = browserWsUrl, onConnected = { connected.countDown() }, onError = { connected.countDown() })
        await(connected, "connection")
        assertTrue(cm.connectionState.isActive())

        val disc = CountDownLatch(1)
        val targets = AtomicReference<List<TargetInfo>>()
        cm.discoverTargets(callback = { t -> targets.set(t); disc.countDown() }, onError = { fail("discover: ${it.message}") })
        await(disc, "target discovery")
        val page = targets.get().first { it.isPage }
        cm.selectTarget(page.targetId)

        val attached = CountDownLatch(1)
        val sessionRef = AtomicReference<SessionInfo?>()
        cm.attachToTarget(
            onAttached = { s -> sessionRef.set(s); attached.countDown() },
            onError = { e -> attached.countDown() },
        )
        await(attached, "target attach")
        val session = sessionRef.get()!!
        val inspector = DomInspector(cm.dispatcher, cm.eventRouter)
        inspector.bindSession(session)
        return session to inspector
    }

    private fun waitForDomReady(inspector: DomInspector) {
        val deadline = System.currentTimeMillis() + 15000
        while (inspector.state() != DomState.READY && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(DomState.READY, inspector.state())
        assertNotNull("Document must be loaded", inspector.tree.document)
    }

    private fun waitForTag(inspector: DomInspector, tag: String): DomNode? {
        val deadline = System.currentTimeMillis() + 10000
        while (inspector.tree.allNodes.none { it.tagName == tag } && System.currentTimeMillis() < deadline) {
            val toExpand = inspector.tree.allNodes.firstOrNull { it.isElement && inspector.tree.hasUnloadedChildren(it.nodeId) }
                ?: break
            inspector.expandNode(toExpand.nodeId)
            Thread.sleep(50)
        }
        return inspector.tree.allNodes.firstOrNull { it.tagName == tag }
    }

    private fun waitForCapability(css: CssInspector, status: CssCapabilityStatus) {
        val deadline = System.currentTimeMillis() + 15000
        while (css.capability != status && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(status, css.capability)
    }

    private fun waitForPseudoPhase(css: CssInspector, phase: CssPseudoStatePhase) {
        val deadline = System.currentTimeMillis() + 15000
        while (css.inspectionState.pseudoState.phase != phase && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(phase, css.inspectionState.pseudoState.phase)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for: $what", condition())
    }

    private fun computedColor(css: CssInspector): String? =
        css.inspectionState.computedStyle?.firstOrNull { it.name == "color" }?.value

    private fun waitForComputedColor(css: CssInspector, expected: String) {
        waitFor("computed color == $expected") { computedColor(css) == expected }
    }

    /** Injects a test page with :hover / :active / :focus rules. */
    private fun injectTestPage(cm: ConnectionManager, session: SessionInfo) {
        val expression = """
            document.head.querySelectorAll('style').forEach(function(st){ st.remove(); });
            var s = document.createElement('style'); s.id = 'p3c-style';
            s.textContent = `
              #box { color: black; }
              #box:hover { color: red; }
              #box:active { color: blue; }
              #box:focus { outline: 2px solid blue; }
            `;
            document.head.appendChild(s);
            document.body.innerHTML = "<div id='box' tabindex='0'>Box</div>";
        """
        val latch = CountDownLatch(1)
        cm.dispatcher.sendCommand(
            "Runtime.evaluate",
            mapOf("expression" to expression),
            sessionId = session.sessionId,
            callback = { latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun phase3c_pseudoStateForcing_againstRealChrome() {
        val cm = newManager()
        val (session, inspector) = connectAttachPage(cm)
        waitForDomReady(inspector)
        val css = CssInspector(cm.dispatcher, cm.eventRouter, inspector)
        waitForCapability(css, CssCapabilityStatus.SUPPORTED)

        injectTestPage(cm, session)
        inspector.refresh()
        waitForDomReady(inspector)

        val box = waitForTag(inspector, "DIV")
        assertNotNull("box must load", box)
        inspector.selectNode(box!!.nodeId)
        waitFor("computed styles load") { css.inspectionState.computedStylePhase == CssInspectionPhase.LOADED }
        waitForComputedColor(css, "rgb(0, 0, 0)")

        // Apply :hover → real page turns red.
        val latch1 = CountDownLatch(1)
        css.setPseudoClass(box.nodeId, CssPseudoClass.HOVER, true) { latch1.countDown() }
        await(latch1, "force hover")
        waitForPseudoPhase(css, CssPseudoStatePhase.APPLIED)
        assertTrue(css.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
        waitForComputedColor(css, "rgb(255, 0, 0)")

        // Matched rules reflect the forced :hover rule.
        waitFor(":hover rule matched") {
            css.inspectionState.styles?.matchedRules?.any { it.selectorText == "#box:hover" } == true
        }

        // Add :active (full set replaces) → :active wins (source order) → blue.
        val latch2 = CountDownLatch(1)
        css.setPseudoClass(box.nodeId, CssPseudoClass.ACTIVE, true) { latch2.countDown() }
        await(latch2, "force active")
        waitForPseudoPhase(css, CssPseudoStatePhase.APPLIED)
        assertTrue(css.inspectionState.pseudoState.isForced(CssPseudoClass.ACTIVE))
        assertTrue(css.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
        waitForComputedColor(css, "rgb(0, 0, 255)")

        // Apply :focus (outline applies; color unchanged by the focus rule).
        val latch3 = CountDownLatch(1)
        css.setPseudoClass(box.nodeId, CssPseudoClass.FOCUS, true) { latch3.countDown() }
        await(latch3, "force focus")
        waitForPseudoPhase(css, CssPseudoStatePhase.APPLIED)
        assertTrue(css.inspectionState.pseudoState.isForced(CssPseudoClass.FOCUS))
        waitForComputedColor(css, "rgb(0, 0, 255)")
        waitFor("outline applied when :focus forced") {
            css.inspectionState.computedStyle?.firstOrNull { it.name == "outline-style" }?.value == "solid"
        }

        // Remove :active → hover remains → red.
        val latch4 = CountDownLatch(1)
        css.setPseudoClass(box.nodeId, CssPseudoClass.ACTIVE, false) { latch4.countDown() }
        await(latch4, "remove active")
        waitForPseudoPhase(css, CssPseudoStatePhase.APPLIED)
        assertFalse(css.inspectionState.pseudoState.isForced(CssPseudoClass.ACTIVE))
        assertTrue(css.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
        waitForComputedColor(css, "rgb(255, 0, 0)")

        // Remove :hover and :focus → back to black.
        val latch5 = CountDownLatch(1)
        css.setPseudoClass(box.nodeId, CssPseudoClass.HOVER, false) { latch5.countDown() }
        await(latch5, "remove hover")
        waitForPseudoPhase(css, CssPseudoStatePhase.APPLIED)
        val latch6 = CountDownLatch(1)
        css.setPseudoClass(box.nodeId, CssPseudoClass.FOCUS, false) { latch6.countDown() }
        await(latch6, "remove focus")
        waitForPseudoPhase(css, CssPseudoStatePhase.APPLIED)
        assertFalse(css.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))
        assertFalse(css.inspectionState.pseudoState.isForced(CssPseudoClass.ACTIVE))
        assertFalse(css.inspectionState.pseudoState.isForced(CssPseudoClass.FOCUS))
        waitForComputedColor(css, "rgb(0, 0, 0)")

        inspector.detach()
        cm.disconnect()
    }

    @Test
    fun phase3c_selectionChangeClearsPseudoState_againstRealChrome() {
        val cm = newManager()
        val (session, inspector) = connectAttachPage(cm)
        waitForDomReady(inspector)
        val css = CssInspector(cm.dispatcher, cm.eventRouter, inspector)
        waitForCapability(css, CssCapabilityStatus.SUPPORTED)

        injectTestPage(cm, session)
        inspector.refresh()
        waitForDomReady(inspector)

        val box = waitForTag(inspector, "DIV")
        val html = waitForTag(inspector, "HTML")
        assertNotNull(box)
        assertNotNull(html)

        inspector.selectNode(box!!.nodeId)
        waitFor("computed styles load") { css.inspectionState.computedStylePhase == CssInspectionPhase.LOADED }
        val latch = CountDownLatch(1)
        css.setPseudoClass(box.nodeId, CssPseudoClass.HOVER, true) { latch.countDown() }
        await(latch, "force hover")
        waitForPseudoPhase(css, CssPseudoStatePhase.APPLIED)
        assertTrue(css.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER))

        // Select a different node — pseudo-state must reset and not transfer.
        inspector.selectNode(html!!.nodeId)
        waitFor("pseudo-state reset on selection change") {
            !css.inspectionState.pseudoState.isForced(CssPseudoClass.HOVER)
        }
        assertNotEquals(css.inspectionState.pseudoState.phase, CssPseudoStatePhase.APPLIED)

        // Re-select the box — clean pseudo-state, no stale forcing.
        inspector.selectNode(box.nodeId)
        waitFor("computed styles reload for box") { css.inspectionState.computedStylePhase == CssInspectionPhase.LOADED }
        waitForComputedColor(css, "rgb(0, 0, 0)")

        inspector.detach()
        cm.disconnect()
    }
}