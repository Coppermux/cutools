package com.coppermux.cutools.core.target

import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.core.transport.DispatcherConfig
import com.coppermux.cutools.core.transport.FakeCdpTransport
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class TargetManagerTest {

    private lateinit var transport: FakeCdpTransport
    private lateinit var eventRouter: CdpEventRouter
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var targetManager: TargetManager

    @Before
    fun setUp() {
        transport = FakeCdpTransport()
        eventRouter = CdpEventRouter()
        val executor = Executors.newSingleThreadScheduledExecutor()
        val config = DispatcherConfig(defaultTimeoutMs = 1000, executor = executor)
        dispatcher = CommandDispatcher(transport, eventRouter, config)
        transport.connect("ws://localhost:9222", dispatcher)
        targetManager = TargetManager(dispatcher, eventRouter)
        targetManager.registerTargetDomainEvents()
    }

    private fun createTargetEvent(targetId: String, type: String): Event =
        Event(method = "Target.targetCreated", params = mapOf("targetInfo" to mapOf(
            "targetId" to targetId, "type" to type
        )))

    private fun extractId(json: String): Long {
        val map = com.coppermux.cutools.core.model.Protocol.moshi.adapter(Map::class.java).fromJson(json)
        return (map?.get("id") as Number).toLong()
    }

    // ============== Target discovery via events ==============

    @Test
    fun targetCreated_viaEvent_isAddedToTargets() {
        var created: TargetInfo? = null
        targetManager.addListener(object : TargetManagerListener {
            override fun onTargetCreated(target: TargetInfo) { created = target }
            override fun onTargetUpdated(target: TargetInfo) {}
            override fun onTargetDestroyed(targetId: String) {}
            override fun onTargetSelected(target: TargetInfo) {}
            override fun onTargetsUpdated(targets: List<TargetInfo>) {}
            override fun onAttached(session: SessionInfo) {}
            override fun onDetached(targetId: String) {}
            override fun onCleared() {}
            override fun onError(error: Throwable) {}
        })

        eventRouter.handleEvent(Event(
            method = "Target.targetCreated",
            params = mapOf("targetInfo" to mapOf(
                "targetId" to "t1",
                "type" to "page",
                "title" to "Test Page",
                "url" to "http://example.com",
                "webSocketDebuggerUrl" to "ws://localhost:9222/devtools/page/t1",
            ))
        ))

        assertNotNull(created)
        assertEquals("t1", created!!.targetId)
        assertEquals("page", created!!.type)
        assertEquals("Test Page", created!!.title)
        assertEquals(1, targetManager.discoveredTargets.size)
    }

    // ============== Target update ==============

    @Test
    fun targetInfoUpdated_viaEvent_updatesExistingTarget() {
        // Create
        eventRouter.handleEvent(createTargetEvent("t1", "page"))
        assertEquals("page", targetManager.discoveredTargets[0].type)

        // Update
        eventRouter.handleEvent(Event(
            method = "Target.targetInfoUpdated",
            params = mapOf("targetInfo" to mapOf(
                "targetId" to "t1",
                "type" to "page",
                "title" to "Updated Title",
                "url" to "http://updated.com",
            ))
        ))

        val target = targetManager.discoveredTargets.find { it.targetId == "t1" }
        assertNotNull(target)
        assertEquals("Updated Title", target!!.title)
        assertEquals("http://updated.com", target.url)
    }

    // ============== Target removal ==============

    @Test
    fun targetDestroyed_viaEvent_removesTarget() {
        eventRouter.handleEvent(createTargetEvent("t1", "page"))
        assertEquals(1, targetManager.discoveredTargets.size)

        eventRouter.handleEvent(Event(
            method = "Target.targetDestroyed",
            params = mapOf("targetId" to "t1")
        ))
        assertEquals(0, targetManager.discoveredTargets.size)
    }

    // ============== Target selection ==============

    @Test
    fun selectTarget_storesSelectedTarget() {
        eventRouter.handleEvent(createTargetEvent("t1", "page"))
        targetManager.selectTarget("t1")
        val selected = targetManager.selectedTarget
        assertNotNull(selected)
        assertEquals("t1", selected!!.targetId)
    }

    @Test
    fun selectNonExistentTarget_notifiesError() {
        val listener = object : TargetManagerListener {
            var capturedError: Throwable? = null
            override fun onTargetCreated(target: TargetInfo) {}
            override fun onTargetUpdated(target: TargetInfo) {}
            override fun onTargetDestroyed(targetId: String) {}
            override fun onTargetSelected(target: TargetInfo) {}
            override fun onTargetsUpdated(targets: List<TargetInfo>) {}
            override fun onAttached(session: SessionInfo) {}
            override fun onDetached(targetId: String) {}
            override fun onCleared() {}
            override fun onError(error: Throwable) { capturedError = error }
        }
        targetManager.addListener(listener)
        targetManager.selectTarget("nonexistent")
        assertNotNull(listener.capturedError)
    }

    // ============== Session management ==============

    @Test
    fun attachToSelectedTarget_createsSession() {
        eventRouter.handleEvent(createTargetEvent("t1", "page"))
        targetManager.selectTarget("t1")

        val attachResult = AtomicReference<Result<SessionInfo>?>()
        targetManager.attachToSelectedTarget { attachResult.set(it) }

        val commandJson = transport.sentMessages.last()
        val id = extractId(commandJson)
        transport.deliverMessage("""{"id":$id,"result":{"sessionId":"SESSION123"}}""")

        Thread.sleep(50)
        val result = attachResult.get()
        assertNotNull(result)
        assertTrue(result!!.isSuccess)
        val session = result.getOrThrow()
        assertEquals("SESSION123", session.sessionId)
        assertEquals("t1", session.targetId)
        assertTrue(targetManager.isAttached)
    }

    @Test
    fun detachFromSelectedTarget_clearsSession() {
        eventRouter.handleEvent(createTargetEvent("t1", "page"))
        targetManager.selectTarget("t1")

        val attachResult = AtomicReference<Result<SessionInfo>?>()
        targetManager.attachToSelectedTarget { attachResult.set(it) }
        val attachId = extractId(transport.sentMessages.last())
        transport.deliverMessage("""{"id":$attachId,"result":{"sessionId":"S1"}}""")

        Thread.sleep(50)
        assertTrue(attachResult.get()?.isSuccess == true)
        assertTrue(targetManager.isAttached)

        // Detach
        val detachResult = AtomicReference<Result<Unit>?>()
        targetManager.detachFromSelectedTarget { detachResult.set(it) }
        val detachId = extractId(transport.sentMessages.last())
        transport.deliverMessage("""{"id":$detachId,"result":{}}""")

        Thread.sleep(50)
        assertTrue(detachResult.get()?.isSuccess == true)
        assertFalse(targetManager.isAttached)
    }

    // ============== Session association ==============

    @Test
    fun sessionAssociation_targetIdMapsToSessionId() {
        eventRouter.handleEvent(createTargetEvent("t1", "page"))
        targetManager.selectTarget("t1")

        val sessionRef = AtomicReference<Result<SessionInfo>?>()
        targetManager.attachToSelectedTarget { sessionRef.set(it) }
        val id = extractId(transport.sentMessages.last())
        transport.deliverMessage("""{"id":$id,"result":{"sessionId":"SESSION_XYZ"}}""")

        Thread.sleep(50)
        val session = targetManager.activeSession
        assertNotNull(session)
        assertEquals("SESSION_XYZ", session!!.sessionId)
        assertEquals("t1", session.targetId)
    }

    // ============== Clear all state ==============

    @Test
    fun clearAllState_removesAllTargetsAndSessions() {
        eventRouter.handleEvent(createTargetEvent("t1", "page"))
        targetManager.selectTarget("t1")

        targetManager.clearAllState()

        assertEquals(0, targetManager.discoveredTargets.size)
        assertNull(targetManager.selectedTarget)
        assertNull(targetManager.activeSession)
        assertFalse(targetManager.isAttached)
    }

    // ============== Unknown fields don't break parsing ==============

    @Test
    fun targetCreated_withUnknownFields_parsesSuccessfully() {
        eventRouter.handleEvent(Event(
            method = "Target.targetCreated",
            params = mapOf("targetInfo" to mapOf(
                "targetId" to "t1",
                "type" to "page",
                "unknownField" to "ignored",
                "anotherUnknown" to 42,
            ))
        ))
        assertEquals(1, targetManager.discoveredTargets.size)
        assertEquals("t1", targetManager.discoveredTargets[0].targetId)
    }

    // ============== Target.attachedToTarget event handling ==============

    @Test
    fun attachedToTarget_viaEvent_tracksSessionAndNotifiesListener() {
        val attachedSession = AtomicReference<SessionInfo?>()
        targetManager.addListener(object : TargetManagerListener {
            override fun onTargetCreated(target: TargetInfo) {}
            override fun onTargetUpdated(target: TargetInfo) {}
            override fun onTargetDestroyed(targetId: String) {}
            override fun onTargetSelected(target: TargetInfo) {}
            override fun onTargetsUpdated(targets: List<TargetInfo>) {}
            override fun onAttached(session: SessionInfo) { attachedSession.set(session) }
            override fun onDetached(targetId: String) {}
            override fun onCleared() {}
            override fun onError(error: Throwable) {}
        })

        eventRouter.handleEvent(Event(
            method = "Target.attachedToTarget",
            params = mapOf(
                "sessionId" to "sess-100",
                "targetInfo" to mapOf(
                    "targetId" to "t100",
                    "type" to "page",
                    "title" to "Attached Page",
                    "url" to "https://example.com",
                    "attached" to true,
                ),
                "waitingForDebugger" to false,
            )
        ))

        assertNotNull(attachedSession.get())
        assertEquals("sess-100", attachedSession.get()!!.sessionId)
        assertEquals("t100", attachedSession.get()!!.targetId)
        assertTrue(targetManager.isAttached)
        assertEquals("sess-100", targetManager.activeSession?.sessionId)
    }

    // ============== Target.detachedFromTarget with sessionId only ==============

    @Test
    fun detachedFromTarget_bySessionIdOnly_clearsSessionAndNotifiesListener() {
        // Attach first
        eventRouter.handleEvent(Event(
            method = "Target.attachedToTarget",
            params = mapOf(
                "sessionId" to "sess-200",
                "targetInfo" to mapOf(
                    "targetId" to "t200",
                    "type" to "page",
                ),
            )
        ))
        assertTrue(targetManager.isAttached)

        val detachedTargetId = AtomicReference<String?>()
        targetManager.addListener(object : TargetManagerListener {
            override fun onTargetCreated(target: TargetInfo) {}
            override fun onTargetUpdated(target: TargetInfo) {}
            override fun onTargetDestroyed(targetId: String) {}
            override fun onTargetSelected(target: TargetInfo) {}
            override fun onTargetsUpdated(targets: List<TargetInfo>) {}
            override fun onAttached(session: SessionInfo) {}
            override fun onDetached(targetId: String) { detachedTargetId.set(targetId) }
            override fun onCleared() {}
            override fun onError(error: Throwable) {}
        })

        // Detach with sessionId only
        eventRouter.handleEvent(Event(
            method = "Target.detachedFromTarget",
            params = mapOf("sessionId" to "sess-200")
        ))

        assertEquals("t200", detachedTargetId.get())
        assertFalse(targetManager.isAttached)
        assertNull(targetManager.activeSession)
    }

    // ============== Target.targetDestroyed cleans up attached session ==============

    @Test
    fun targetDestroyed_whenAttached_clearsSessionAndNotifiesDetached() {
        // Attach first
        eventRouter.handleEvent(Event(
            method = "Target.attachedToTarget",
            params = mapOf(
                "sessionId" to "sess-300",
                "targetInfo" to mapOf(
                    "targetId" to "t300",
                    "type" to "page",
                ),
            )
        ))
        assertTrue(targetManager.isAttached)

        val detachedTargetId = AtomicReference<String?>()
        val destroyedTargetId = AtomicReference<String?>()
        targetManager.addListener(object : TargetManagerListener {
            override fun onTargetCreated(target: TargetInfo) {}
            override fun onTargetUpdated(target: TargetInfo) {}
            override fun onTargetDestroyed(targetId: String) { destroyedTargetId.set(targetId) }
            override fun onTargetSelected(target: TargetInfo) {}
            override fun onTargetsUpdated(targets: List<TargetInfo>) {}
            override fun onAttached(session: SessionInfo) {}
            override fun onDetached(targetId: String) { detachedTargetId.set(targetId) }
            override fun onCleared() {}
            override fun onError(error: Throwable) {}
        })

        // Destroy target
        eventRouter.handleEvent(Event(
            method = "Target.targetDestroyed",
            params = mapOf("targetId" to "t300")
        ))

        assertEquals("t300", destroyedTargetId.get())
        assertEquals("t300", detachedTargetId.get())
        assertFalse(targetManager.isAttached)
        assertNull(targetManager.activeSession)
    }

    @Test
    fun targetDestroyed_whenTransientTarget_doesNotNotifyDetachedAndPreservesActiveSession() {
        // Attach active page target first
        eventRouter.handleEvent(Event(
            method = "Target.attachedToTarget",
            params = mapOf(
                "sessionId" to "sess-main",
                "targetInfo" to mapOf(
                    "targetId" to "t-main",
                    "type" to "page",
                ),
            )
        ))
        assertTrue(targetManager.isAttached)
        assertEquals("t-main", targetManager.activeSession?.targetId)

        // Transient target arrives (e.g., service worker / iframe)
        eventRouter.handleEvent(Event(
            method = "Target.attachedToTarget",
            params = mapOf(
                "sessionId" to "sess-transient",
                "targetInfo" to mapOf(
                    "targetId" to "t-transient",
                    "type" to "service_worker",
                ),
            )
        ))
        // Active session must remain t-main
        assertEquals("t-main", targetManager.activeSession?.targetId)

        val detachedTargetId = AtomicReference<String?>()
        val destroyedTargetId = AtomicReference<String?>()
        targetManager.addListener(object : TargetManagerListener {
            override fun onTargetCreated(target: TargetInfo) {}
            override fun onTargetUpdated(target: TargetInfo) {}
            override fun onTargetDestroyed(targetId: String) { destroyedTargetId.set(targetId) }
            override fun onTargetSelected(target: TargetInfo) {}
            override fun onTargetsUpdated(targets: List<TargetInfo>) {}
            override fun onAttached(session: SessionInfo) {}
            override fun onDetached(targetId: String) { detachedTargetId.set(targetId) }
            override fun onCleared() {}
            override fun onError(error: Throwable) {}
        })

        // Transient target is destroyed
        eventRouter.handleEvent(Event(
            method = "Target.targetDestroyed",
            params = mapOf("targetId" to "t-transient")
        ))

        assertEquals("t-transient", destroyedTargetId.get())
        // onDetached must NOT be notified for a transient target
        assertNull(detachedTargetId.get())
        // Active session must still be attached and intact
        assertTrue(targetManager.isAttached)
        assertEquals("t-main", targetManager.activeSession?.targetId)
    }

    @Test
    fun attachToSelectedTarget_whenSessionAlreadyExists_notifiesListenersWithExistingSession() {
        // Target is discovered and attached initially
        eventRouter.handleEvent(Event(
            method = "Target.attachedToTarget",
            params = mapOf(
                "sessionId" to "sess-existing",
                "targetInfo" to mapOf(
                    "targetId" to "t-reuse",
                    "type" to "page",
                ),
            )
        ))

        targetManager.selectTarget("t-reuse")

        val attachedSession = AtomicReference<SessionInfo?>()
        targetManager.addListener(object : TargetManagerListener {
            override fun onTargetCreated(target: TargetInfo) {}
            override fun onTargetUpdated(target: TargetInfo) {}
            override fun onTargetDestroyed(targetId: String) {}
            override fun onTargetSelected(target: TargetInfo) {}
            override fun onTargetsUpdated(targets: List<TargetInfo>) {}
            override fun onAttached(session: SessionInfo) { attachedSession.set(session) }
            override fun onDetached(targetId: String) {}
            override fun onCleared() {}
            override fun onError(error: Throwable) {}
        })

        attachedSession.set(null)

        // Re-attaching to existing session must re-notify listeners
        var callbackResult: Result<SessionInfo>? = null
        targetManager.attachToSelectedTarget { result ->
            callbackResult = result
        }

        assertTrue(callbackResult?.isSuccess == true)
        assertEquals("sess-existing", callbackResult?.getOrNull()?.sessionId)
        assertNotNull("Listeners must be notified when reusing existing session", attachedSession.get())
        assertEquals("sess-existing", attachedSession.get()?.sessionId)
    }

    @Test
    fun findBestPageTarget_prefersVisibleAndWindowAttachedTarget() {
        val dormant = TargetInfo(
            targetId = "t-dormant",
            type = "page",
            url = "https://example.com",
            isWindowAttached = false,
            isVisible = false,
        )
        val visible = TargetInfo(
            targetId = "t-visible",
            type = "page",
            url = "https://example.com",
            isWindowAttached = true,
            isVisible = true,
        )

        // Add both to targets map
        targetManager.updateTargetMetadata(dormant.targetId, "{\"attached\":false,\"visible\":false}")
        // Discover targets
        val targetsField = TargetManager::class.java.getDeclaredField("targets")
        targetsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = targetsField.get(targetManager) as java.util.concurrent.ConcurrentHashMap<String, TargetInfo>
        map[dormant.targetId] = dormant
        map[visible.targetId] = visible

        val best = targetManager.findBestPageTarget("https://example.com")
        assertEquals("t-visible", best?.targetId)
    }
}
