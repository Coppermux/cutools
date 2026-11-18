package com.coppermux.cutools.tools.application.engine

import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.tools.application.models.ServiceWorkerActionResult
import com.coppermux.cutools.tools.application.models.ServiceWorkerActionStatus
import com.coppermux.cutools.tools.application.models.ServiceWorkerCapabilityStatus
import com.coppermux.cutools.tools.application.models.ServiceWorkerErrorLogModel
import com.coppermux.cutools.tools.application.models.ServiceWorkerRegistrationModel
import com.coppermux.cutools.tools.application.models.ServiceWorkerState
import com.coppermux.cutools.tools.application.models.ServiceWorkerVersionModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates real-time service worker inspection and lifecycle management for
 * the currently attached page target via the CDP `ServiceWorker` domain.
 *
 * Sub-Phase 6E:
 * - [enable] / [disable] manage `ServiceWorker` domain notifications.
 * - [unregister] issues `ServiceWorker.unregister` by `scopeURL`.
 * - [updateRegistration] issues `ServiceWorker.updateRegistration` by `scopeURL`.
 * - [stopWorker] issues `ServiceWorker.stopWorker` by `versionId`.
 *   CDP failures are mapped to explicit [ServiceWorkerActionResult] statuses
 *   (`UNSUPPORTED`, `UNAVAILABLE`, `FAILED`) — no mock or hardcoded service
 *   worker values are ever returned (No-Fake-Capability).
 * - The [CdpServiceWorkerEventListener] routes genuine `ServiceWorker` events
 *   (`workerRegistrationUpdated`, `workerVersionUpdated`,
 *   `workerErrorReported`) to atomic in-memory stores, so the inspected
 *   registrations / versions / error logs stay current without state fabrication.
 * - Session attach/detach lifecycle resets all target-scoped service worker
 *   state and re-binds event subscriptions; a generation counter guards stale
 *   async results/events delivered after detach.
 *
 * Threading: state fields are `@Volatile`; the registration and version stores
 * are [ConcurrentHashMap]s and the error log a [CopyOnWriteArrayList] so
 * concurrent event streams never lose an update. All Chrome communication
 * reuses the shared [CommandDispatcher] — no second transport is created.
 */
class ServiceWorkerEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `ServiceWorker` domain capability for the current session/target. */
    @Volatile
    var capability: ServiceWorkerCapabilityStatus? = null
        private set

    /** Immutable snapshot of the inspected service worker state. */
    @Volatile
    var state: ServiceWorkerState = ServiceWorkerState.empty()
        private set

    /** Guards stale async results/events after detach / re-bind. */
    private val generation = AtomicInteger(0)

    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    /** Atomic backing stores for the inspected registrations, versions, and error log. */
    private val registrationStore = ConcurrentHashMap<String, ServiceWorkerRegistrationModel>()
    private val versionStore = ConcurrentHashMap<String, ServiceWorkerVersionModel>()
    private val errorLog = CopyOnWriteArrayList<ServiceWorkerErrorLogModel>()

    private val listener = CdpServiceWorkerEventListener(object : ServiceWorkerStore {
        override fun onRegistrationsUpdated(registrations: List<ServiceWorkerRegistrationModel>) {
            registrations.forEach { registrationStore[it.registrationId] = it }
            refreshState()
        }

        override fun onVersionsUpdated(versions: List<ServiceWorkerVersionModel>) {
            versions.forEach { versionStore[it.versionId] = it }
            refreshState()
        }

        override fun onErrorReported(error: ServiceWorkerErrorLogModel) {
            errorLog.add(error)
            refreshState()
        }
    })

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session, resets prior target state, and enables the domain. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        capability = null
        registrationStore.clear()
        versionStore.clear()
        errorLog.clear()
        updateState(
            ServiceWorkerState(
                sessionId = newSession.sessionId,
                targetId = newSession.targetId,
            )
        )
        clearSubscriptions()
        registerSubscriptions()
        sendEnable()
    }

    /** Detaches, clearing all target-scoped service worker state and subscriptions. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        capability = null
        registrationStore.clear()
        versionStore.clear()
        errorLog.clear()
        updateState(ServiceWorkerState())
        clearSubscriptions()
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    /** Unsubscribes from all events. Called during module shutdown. */
    fun shutdown() {
        clearSubscriptions()
    }

    // ================================================================
    // Domain lifecycle
    // ================================================================

    /**
     * Enables `ServiceWorker` domain notifications via `ServiceWorker.enable`.
     * Protocol failures resolve to an explicit [ServiceWorkerActionResult] status.
     */
    fun enable(callback: (ServiceWorkerActionResult<Unit>) -> Unit = {}) {
        sendEnable(callback)
    }

    /**
     * Disables `ServiceWorker` domain notifications via `ServiceWorker.disable`.
     */
    fun disable(callback: (ServiceWorkerActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = ServiceWorkerCapabilityStatus.UNAVAILABLE
            callback(ServiceWorkerActionResult.unavailable("ServiceWorker domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "ServiceWorker.disable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    updateState(state.copy(enabled = false))
                    callback(ServiceWorkerActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "ServiceWorker.disable"))
                }
            }
        }
    }

    private fun sendEnable(callback: (ServiceWorkerActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = ServiceWorkerCapabilityStatus.UNAVAILABLE
            callback(ServiceWorkerActionResult.unavailable("ServiceWorker domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "ServiceWorker.enable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = ServiceWorkerCapabilityStatus.SUPPORTED
                    updateState(state.copy(enabled = true, capability = ServiceWorkerCapabilityStatus.SUPPORTED))
                    callback(ServiceWorkerActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "ServiceWorker.enable"))
                }
            }
        }
    }

    // ================================================================
    // Lifecycle management
    // ================================================================

    /**
     * Unregisters a service worker registration by its [scopeURL] via
     * `ServiceWorker.unregister`.
     */
    fun unregister(scopeUrl: String, callback: (ServiceWorkerActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = ServiceWorkerCapabilityStatus.UNAVAILABLE
            callback(ServiceWorkerActionResult.unavailable("ServiceWorker domain is not bound to an active session"))
            return
        }
        if (scopeUrl.isBlank()) {
            callback(ServiceWorkerActionResult.failed("No scope URL provided to unregister"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "ServiceWorker.unregister",
            params = mapOf("scopeURL" to scopeUrl),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = ServiceWorkerCapabilityStatus.SUPPORTED
                    callback(ServiceWorkerActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "ServiceWorker.unregister"))
                }
            }
        }
    }

    /**
     * Triggers an update check for a service worker registration by its
     * [scopeURL] via `ServiceWorker.updateRegistration`.
     */
    fun updateRegistration(scopeUrl: String, callback: (ServiceWorkerActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = ServiceWorkerCapabilityStatus.UNAVAILABLE
            callback(ServiceWorkerActionResult.unavailable("ServiceWorker domain is not bound to an active session"))
            return
        }
        if (scopeUrl.isBlank()) {
            callback(ServiceWorkerActionResult.failed("No scope URL provided to update registration"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "ServiceWorker.updateRegistration",
            params = mapOf("scopeURL" to scopeUrl),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = ServiceWorkerCapabilityStatus.SUPPORTED
                    callback(ServiceWorkerActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "ServiceWorker.updateRegistration"))
                }
            }
        }
    }

    /**
     * Stops a running worker instance by its [versionId] via
     * `ServiceWorker.stopWorker`.
     */
    fun stopWorker(versionId: String, callback: (ServiceWorkerActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = ServiceWorkerCapabilityStatus.UNAVAILABLE
            callback(ServiceWorkerActionResult.unavailable("ServiceWorker domain is not bound to an active session"))
            return
        }
        if (versionId.isBlank()) {
            callback(ServiceWorkerActionResult.failed("No version ID provided to stop worker"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "ServiceWorker.stopWorker",
            params = mapOf("versionId" to versionId),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = ServiceWorkerCapabilityStatus.SUPPORTED
                    callback(ServiceWorkerActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "ServiceWorker.stopWorker"))
                }
            }
        }
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("ServiceWorker.workerRegistrationUpdated") { event -> onServiceWorkerEvent(event) }
        subscriptions += eventRouter.subscribe("ServiceWorker.workerVersionUpdated") { event -> onServiceWorkerEvent(event) }
        subscriptions += eventRouter.subscribe("ServiceWorker.workerErrorReported") { event -> onServiceWorkerEvent(event) }
    }

    /** Applies a ServiceWorker event if it belongs to the active session. */
    private fun onServiceWorkerEvent(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        listener.apply(event, active)
    }

    // ================================================================
    // Parsing & error mapping
    // ================================================================

    /**
     * Maps a CDP command failure to an explicit [ServiceWorkerActionResult]
     * status. A method-not-found error (`-32601`) is
     * [ServiceWorkerActionStatus.UNSUPPORTED]; everything else is
     * [ServiceWorkerActionStatus.FAILED].
     */
    private fun classifyFailure(error: Throwable, method: String): ServiceWorkerActionResult<Nothing> {
        val message = error.message.orEmpty()
        val status = if (message.contains("-32601")) {
            capability = ServiceWorkerCapabilityStatus.UNSUPPORTED
            updateState(state.copy(capability = ServiceWorkerCapabilityStatus.UNSUPPORTED))
            ServiceWorkerActionResult.unsupported<Nothing>("$method is not available on the attached target", error)
        } else {
            capability = ServiceWorkerCapabilityStatus.FAILED
            updateState(state.copy(capability = ServiceWorkerCapabilityStatus.FAILED))
            ServiceWorkerActionResult.failed<Nothing>("$method failed: ${error.message}", error)
        }
        return status
    }

    // ================================================================
    // State helpers
    // ================================================================

    /** Publishes a fresh snapshot of the stores as the current state. */
    private fun refreshState() {
        updateState(
            state.copy(
                registrations = registrationStore.toMap(),
                versions = versionStore.toMap(),
                errorLogs = errorLog.toList(),
            )
        )
    }

    private fun updateState(newState: ServiceWorkerState) {
        state = newState
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }
}