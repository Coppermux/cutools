package com.coppermux.cutools.core.transport

import com.coppermux.cutools.core.model.Event
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CdpEventRouterTest {

    private lateinit var router: CdpEventRouter

    @Before
    fun setUp() {
        router = CdpEventRouter()
    }

    // ============== Event classification ==============

    @Test
    fun eventClassification_deliverToSubscriber() {
        var received: Event? = null
        router.subscribe("Target.targetCreated") { event -> received = event }

        val event = Event(method = "Target.targetCreated", params = mapOf("targetId" to "t1"))
        router.handleEvent(event)

        assertNotNull(received)
        assertEquals("Target.targetCreated", received!!.method)
    }

    // ============== Subscription ==============

    @Test
    fun subscribe_receivesMatchingEvents() {
        val received = mutableListOf<Event>()
        router.subscribe("Target.targetCreated") { received.add(it) }

        val e1 = Event(method = "Target.targetCreated", params = mapOf("targetId" to "t1"))
        val e2 = Event(method = "Target.targetDestroyed", params = mapOf("targetId" to "t1"))

        router.handleEvent(e1)
        router.handleEvent(e2)

        assertEquals(1, received.size)
        assertEquals("Target.targetCreated", received[0].method)
    }

    @Test
    fun subscribe_specificMethod_doesNotReceiveOtherMethods() {
        var received = 0
        router.subscribe("Runtime.consoleAPICalled") { received++ }

        val event = Event(method = "Target.targetCreated", params = emptyMap<String, Any?>())
        router.handleEvent(event)

        assertEquals(0, received)
    }

    // ============== Multiple subscribers ==============

    @Test
    fun multipleSubscribers_allReceiveEvent() {
        var count = 0
        router.subscribe("Target.targetCreated") { count++ }
        router.subscribe("Target.targetCreated") { count++ }
        router.subscribe("Target.targetCreated") { count++ }

        val event = Event(method = "Target.targetCreated", params = emptyMap<String, Any?>())
        router.handleEvent(event)

        assertEquals(3, count)
    }

    @Test
    fun multipleSubscribersOnDifferentMethods_receiveCorrectEvents() {
        var countA = 0
        var countB = 0
        router.subscribe("EventA") { countA++ }
        router.subscribe("EventB") { countB++ }

        router.handleEvent(Event("EventA", emptyMap<String, Any?>()))
        router.handleEvent(Event("EventB", emptyMap<String, Any?>()))
        router.handleEvent(Event("EventA", emptyMap<String, Any?>()))

        assertEquals(2, countA)
        assertEquals(1, countB)
    }

    // ============== Unsubscription ==============

    @Test
    fun unsubscribe_stopsReceivingEvents() {
        var count = 0
        val subscription = router.subscribe("Target.targetCreated") { count++ }

        val event = Event(method = "Target.targetCreated", params = emptyMap<String, Any?>())
        router.handleEvent(event)
        assertEquals(1, count)

        subscription.unsubscribe()
        router.handleEvent(event)
        assertEquals(1, count)
    }

    @Test
    fun unsubscribeOnly_oneListener_stopsReceiving() {
        var countA = 0
        var countB = 0
        val subA = router.subscribe("Event") { countA++ }
        val subB = router.subscribe("Event") { countB++ }

        val event = Event(method = "Event", params = emptyMap<String, Any?>())
        router.handleEvent(event)
        assertEquals(1, countA)
        assertEquals(1, countB)

        subA.unsubscribe()
        router.handleEvent(event)
        assertEquals(1, countA)
        assertEquals(2, countB)
    }

    // ============== Unknown events ==============

    @Test
    fun unknownEvent_noSubscribers_noCrash() {
        val event = Event(method = "Unknown.domain", params = emptyMap<String, Any?>())
        router.handleEvent(event)
        // No assertion — just verifying no crash
    }

    // ============== Event fields ==============

    @Test
    fun eventWithSessionId_isDelivered() {
        var received: Event? = null
        router.subscribe("Any.event") { received = it }

        val event = Event(
            method = "Any.event",
            params = mapOf("key" to "value"),
            sessionId = "session-123"
        )
        router.handleEvent(event)

        assertNotNull(received)
        assertEquals("session-123", received!!.sessionId)
        assertEquals("value", (received!!.params as Map<*, *>)["key"])
    }

    @Test
    fun subscribeAll_receivesAllEvents() {
        val received = mutableListOf<Event>()
        router.subscribeAll { received.add(it) }

        router.handleEvent(Event("Event.A", emptyMap<String, Any?>()))
        router.handleEvent(Event("Event.B", emptyMap<String, Any?>()))
        router.handleEvent(Event("Event.C", emptyMap<String, Any?>()))

        assertEquals(3, received.size)
    }

    @Test
    fun unsubscribeAllEventListener_stopsReceiving() {
        var count = 0
        val subscription = router.subscribeAll { count++ }

        router.handleEvent(Event("Event.A", emptyMap<String, Any?>()))
        assertEquals(1, count)

        subscription.unsubscribe()
        router.handleEvent(Event("Event.B", emptyMap<String, Any?>()))
        assertEquals(1, count)
    }
}
