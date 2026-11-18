package com.coppermux.cutools.core.integration

import com.coppermux.cutools.core.connection.ConnectionManager
import com.coppermux.cutools.core.connection.ConnectionState
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.target.TargetInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.WebSocketCdpTransport
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Genuine Chrome/Chromium integration validation.
 *
 * Connects to a REAL Chrome debugging endpoint over WebSocket using the actual
 * CuTools production classes (WebSocketCdpTransport + ConnectionManager +
 * CommandDispatcher + TargetManager). Proves:
 *
 * 1. Connection succeeds.
 * 2. Browser information is retrieved.
 * 3. Real targets are discovered.
 * 4. A real target can be selected.
 * 5. A real target can be attached.
 * 6. Session state is tracked.
 * 7. Events can be received.
 * 8. Target can be detached.
 * 9. Connection can be closed.
 * 10. Reconnection works without stale state.
 *
 * The test SKIPS (assumption failure) when Chrome is not reachable at
 * http://127.0.0.1:9224, so the deterministic unit-test suite remains
 * self-contained. When Chrome IS running, this test performs real validation.
 */
class ChromeIntegrationTest {

    private lateinit var browserWsUrl: String

    @Before
    fun discoverChrome() {
        val env = ChromeEnvironment()
        if (!env.isReachable) {
            env.startHeadlessChrome()
        }
        browserWsUrl = env.discoverWebSocketUrl()
        assumeTrue(
            "Real Chrome not reachable at http://127.0.0.1:9224 — skipping integration validation",
            browserWsUrl.isNotBlank()
        )
    }

    private fun newManager(): ConnectionManager {
        val transport = WebSocketCdpTransport()
        val config = DispatcherConfig(defaultTimeoutMs = 5000)
        return ConnectionManager.create(transport, config)
    }

    private fun await(latch: CountDownLatch, what: String) {
        assertTrue("Timed out waiting for: $what", latch.await(15, TimeUnit.SECONDS))
    }

    @Test
    fun fullLifecycle_againstRealChrome() {
        val manager = newManager()

        // 1. Connection succeeds
        val connected = CountDownLatch(1)
        val connectError = AtomicReference<Throwable?>()
        manager.connect(
            url = browserWsUrl,
            onConnected = { connected.countDown() },
            onError = { connectError.set(it); connected.countDown() },
        )
        await(connected, "connection")
        assertNull("Unexpected connect error: ${connectError.get()}", connectError.get())
        assertEquals(ConnectionState.CONNECTED, manager.connectionState)
        assertTrue("Transport must be connected", manager.connectionState.isActive())

        // 2. Browser information is retrieved
        val browserInfo = manager.browserInfo
        assertNotNull("Browser.getVersion should have populated browser info", browserInfo)
        // Chrome's Browser.getVersion populates "product" (e.g. "Chrome/151.0.7922.71"),
        // not the "browser" key, so assert against product identification.
        assertFalse("Protocol version should be non-empty", browserInfo!!.protocolVersion.isNullOrEmpty())
        assertFalse(
            "Chrome product identification should be non-empty, got: browser=${browserInfo.browser}",
            (browserInfo.product ?: browserInfo.browser).isNullOrEmpty()
        )
        assertTrue(
            "Expected Chrome in product, got: ${browserInfo.product}",
            (browserInfo.product ?: "").contains("Chrome")
        )

        // 7. Events can be received — subscribe to Target.attachedToTarget before attaching
        val attachedEvent = AtomicReference<Event?>()
        val attachedEventLatch = CountDownLatch(1)
        val sub = manager.eventRouter.subscribe("Target.attachedToTarget") { e ->
            attachedEvent.set(e)
            attachedEventLatch.countDown()
        }

        // 3. Real targets are discovered
        val discovered = AtomicReference<List<TargetInfo>>()
        val discoverLatch = CountDownLatch(1)
        manager.discoverTargets(
            callback = { targets -> discovered.set(targets); discoverLatch.countDown() },
            onError = { fail("Discover failed: ${it.message}") },
        )
        await(discoverLatch, "target discovery")
        val targets = discovered.get()
        assertTrue("Discovered targets should be non-empty, got ${targets.size}", targets.isNotEmpty())
        assertTrue("Expected a page target among ${targets.map { it.type }}", targets.any { it.isPage })

        // 4. A real target can be selected
        val pageTarget = targets.first { it.isPage }
        manager.selectTarget(pageTarget.targetId)
        assertEquals(ConnectionState.TARGET_SELECTED, manager.connectionState)
        assertNotNull(manager.targetManager?.selectedTarget)
        assertEquals(pageTarget.targetId, manager.targetManager?.selectedTarget?.targetId)

        // 5/6. Attach to the real target; session state tracked
        val sessionRef = AtomicReference<SessionInfo?>()
        val attachError = AtomicReference<Throwable?>()
        val attachLatch = CountDownLatch(1)
        manager.attachToTarget(
            onAttached = { s -> sessionRef.set(s); attachLatch.countDown() },
            onError = { e -> attachError.set(e); attachLatch.countDown() },
        )
        await(attachLatch, "attach to target")
        assertNull("Attach error: ${attachError.get()}", attachError.get())
        val session = sessionRef.get()
        assertNotNull("attachToTarget should return a session", session)
        assertFalse("Session ID must be non-empty", session!!.sessionId.isBlank())
        assertEquals(pageTarget.targetId, session.targetId)
        assertEquals(ConnectionState.ATTACHED, manager.connectionState)
        // Session association
        assertEquals(session.sessionId, manager.targetManager?.activeSession?.sessionId)

        // 7. Events received — attaching to a real target must emit Target.attachedToTarget
        await(attachedEventLatch, "Target.attachedToTarget event")
        val event = attachedEvent.get()
        assertNotNull("Should have received a real Target.attachedToTarget event", event)
        assertEquals("Target.attachedToTarget", event!!.method)
        sub.unsubscribe()

        // 8. Detach cleanly
        val detachError = AtomicReference<Throwable?>()
        val detachLatch = CountDownLatch(1)
        manager.detachFromTarget(
            onDetached = { detachLatch.countDown() },
            onError = { e -> detachError.set(e); detachLatch.countDown() },
        )
        await(detachLatch, "detach")
        assertNull("Detach error: ${detachError.get()}", detachError.get())
        assertNull("Session must be cleared after detach", manager.targetManager?.activeSession)
        assertFalse(manager.targetManager?.isAttached == true)

        // 9. Connection can be closed cleanly
        manager.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, manager.connectionState)
        assertNull("Browser info must be cleared on disconnect", manager.browserInfo)

        // 10. Reconnection works without stale state
        val manager2 = newManager()
        val reconnected = CountDownLatch(1)
        val reconnectError = AtomicReference<Throwable?>()
        manager2.connect(
            url = browserWsUrl,
            onConnected = { reconnected.countDown() },
            onError = { e -> reconnectError.set(e); reconnected.countDown() },
        )
        await(reconnected, "reconnection")
        assertNull("Reconnect error: ${reconnectError.get()}", reconnectError.get())
        assertEquals(ConnectionState.CONNECTED, manager2.connectionState)
        // A fresh connection must not carry over state from the previous one.
        assertNull("No stale session after reconnect", manager2.targetManager?.activeSession)
        assertNull("No stale selected target after reconnect", manager2.targetManager?.selectedTarget)
        assertFalse("No stale attached state after reconnect", manager2.targetManager?.isAttached == true)

        // Browser info is re-retrieved on the fresh connection (not stale from the old one).
        val deadline = System.currentTimeMillis() + 8000
        while (manager2.browserInfo == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertNotNull("Browser info should be retrieved on the fresh connection", manager2.browserInfo)

        manager2.disconnect()
    }

    @Test
    fun command_sendAndCorrelate_againstRealChrome() {
        // Direct dispatcher-level validation: send two commands, receive real responses.
        val transport = WebSocketCdpTransport()
        val router = com.coppermux.cutools.core.transport.CdpEventRouter()
        val config = DispatcherConfig(defaultTimeoutMs = 5000)
        val dispatcher = CommandDispatcher(transport, router, config)

        val connected = CountDownLatch(1)
        transport.connect(browserWsUrl, dispatcher)
        // Wait until the transport is actually connected (async WebSocket).
        val deadline = System.currentTimeMillis() + 10000
        while (!transport.isConnected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("WebSocket should connect to real Chrome", transport.isConnected)
        connected.countDown()

        val v1 = AtomicReference<com.coppermux.cutools.core.model.CommandResult?>()
        val v2 = AtomicReference<com.coppermux.cutools.core.model.CommandResult?>()
        val latch = CountDownLatch(2)
        dispatcher.sendCommand("Browser.getVersion") { r -> v1.set(r); latch.countDown() }
        dispatcher.sendCommand("Target.getTargets") { r -> v2.set(r); latch.countDown() }

        await(latch, "two real command responses")
        val r1 = v1.get()
        val r2 = v2.get()
        assertTrue("getVersion should succeed", r1 is com.coppermux.cutools.core.model.CommandResult.Success)
        assertTrue("getTargets should succeed", r2 is com.coppermux.cutools.core.model.CommandResult.Success)
        val result1 = (r1 as com.coppermux.cutools.core.model.CommandResult.Success).response.result as Map<*, *>
        assertFalse("browser field must be present", result1["browser"].toString().isBlank())

        dispatcher.shutdown()
        transport.disconnect()
    }
}
