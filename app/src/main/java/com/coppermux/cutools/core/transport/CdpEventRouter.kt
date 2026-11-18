package com.coppermux.cutools.core.transport

import com.coppermux.cutools.core.error.ParsingException
import com.coppermux.cutools.core.kernel.DiagnosticKernel
import com.coppermux.cutools.core.kernel.EventProvenance
import com.coppermux.cutools.core.kernel.KernelState
import com.coppermux.cutools.core.model.Event
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Centralized CDP event router (Phase 2).
 *
 * Incoming messages from Chrome are classified as either responses (handled by
 * [CommandDispatcher]) or events. The EventRouter receives classified events
 * and routes them to subscribers based on the event method.
 *
 * Implements session-aware diagnostic event routing and provenance tracking:
 * - Associates incoming events with their originating connection, target, session,
 *   and kernel epoch via [kernel].
 * - Ingress barrier: Suspends diagnostic event ingress during [KernelState.RESETTING].
 * - Drops events reliably identified as belonging to an invalidated session or epoch.
 * - Always delivers connection-level Target domain events to preserve target tracking.
 *
 * Subscriptions are domain-based: subscribers register interest in a specific
 * CDP method (e.g., "Target.targetCreated") and receive matching events.
 */
class CdpEventRouter(
    @Volatile
    var kernel: DiagnosticKernel? = null,
    @Volatile
    var connectionId: String? = null,
) {

    /**
     * Listener for CDP events. Called on the transport callback thread.
     */
    fun interface EventListener {
        fun onEvent(event: Event)
    }

    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<EventListener>>()
    private val allEventListeners = CopyOnWriteArrayList<EventListener>()

    /**
     * Subscribe to events matching [method].
     *
     * @param method The CDP event method (e.g., "Target.targetCreated").
     * @param listener Called when a matching event arrives.
     * @return A subscription handle that can be used to unsubscribe.
     */
    fun subscribe(method: String, listener: EventListener): Subscription {
        val list = subscribers.computeIfAbsent(method) { CopyOnWriteArrayList() }
        list.add(listener)
        return Subscription(method, listener, this)
    }

    /**
     * Subscribe to all CDP events regardless of method.
     */
    fun subscribeAll(listener: EventListener): Subscription {
        allEventListeners.add(listener)
        return Subscription(null, listener, this)
    }

    /**
     * Unsubscribe a previously registered listener.
     */
    fun unsubscribe(method: String?, listener: EventListener) {
        if (method != null) {
            subscribers[method]?.remove(listener)
        }
        allEventListeners.remove(listener)
    }

    /**
     * Called by [CommandDispatcher] when a classified event is received.
     */
    internal fun handleEvent(event: Event) {
        val enriched = enrichProvenance(event)
        if (!shouldDeliver(enriched)) {
            return
        }
        // Deliver to method-specific subscribers
        subscribers[enriched.method]?.forEach { it.onEvent(enriched) }
        // Deliver to all-event listeners
        allEventListeners.forEach { it.onEvent(enriched) }
    }

    private fun enrichProvenance(event: Event): Event {
        if (event.provenance != null) return event
        val k = kernel ?: return event
        val prov = k.resolveProvenance(event.sessionId, connectionId)
        return if (prov != null) {
            event.copy(
                provenance = prov,
                targetId = event.targetId ?: prov.targetId,
            )
        } else if (event.method.startsWith("Target.")) {
            event.copy(
                provenance = EventProvenance(
                    connectionId = connectionId,
                    targetId = event.targetId ?: extractTargetIdFromParams(event.params),
                    cdpSessionId = event.sessionId,
                    kernelEpoch = k.currentEpoch,
                    isSessionScoped = false,
                )
            )
        } else {
            event
        }
    }

    private fun extractTargetIdFromParams(params: Any?): String? {
        val map = params as? Map<*, *> ?: return null
        val targetInfo = map["targetInfo"] as? Map<*, *>
        return (targetInfo?.get("targetId") as? String)
            ?: (map["targetId"] as? String)
    }

    internal fun shouldDeliver(event: Event): Boolean {
        val k = kernel ?: return true
        val snapshot = k.snapshot

        // 1. Connection-level Target domain events must always be delivered
        if (event.method.startsWith("Target.")) {
            return true
        }

        // 2. Ingress barrier: during RESETTING, suspend all diagnostic event ingress
        if (snapshot.state == KernelState.RESETTING) {
            return false
        }

        // 3. Reject events from invalidated sessions / epochs
        val prov = event.provenance
        if (prov != null && prov.isSessionScoped) {
            val eventEpoch = prov.kernelEpoch
            // Must belong to the current active session
            if (eventEpoch == null || eventEpoch != snapshot.epoch || snapshot.state != KernelState.ATTACHED) {
                return false
            }
        } else if (event.sessionId != null) {
            // Event carries a session ID that does not match the active session
            if (snapshot.session == null || event.sessionId != snapshot.session.cdpSessionId) {
                return false
            }
        }

        return true
    }

    /**
     * Called by [CommandDispatcher] when a message could not be classified.
     */
    internal fun handleParsingError(error: ParsingException) {
        // Parsing errors are not swallowed — they are delivered to a special
        // error subscriber if any. In Phase 1, there's no general listener,
        // so we do nothing here. The ConnectionManager logs/observes transport errors.
        // This prevents silent swallowing by at least making the method available
        // for future error listeners.
    }

    /**
     * Number of currently-active subscribers for [method]. Test-only visibility
     * into the subscription registry so lifecycle tests can assert that detach /
     * shutdown removes every subscription (no event listener leaks).
     */
    internal fun subscriptionCount(method: String): Int =
        subscribers[method]?.size ?: 0

    /**
     * Subscription handle for unsubscribing.
     */
    class Subscription(
        private val method: String?,
        private val listener: EventListener,
        private val router: CdpEventRouter,
    ) {
        private var active = true

        fun unsubscribe() {
            if (active) {
                active = false
                router.unsubscribe(method, listener)
            }
        }

        val isActive: Boolean get() = active
    }
}
