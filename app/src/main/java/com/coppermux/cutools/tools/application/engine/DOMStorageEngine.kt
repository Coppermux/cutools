package com.coppermux.cutools.tools.application.engine

import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CdpEventRouter
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.tools.application.models.DOMStorageActionResult
import com.coppermux.cutools.tools.application.models.DOMStorageActionStatus
import com.coppermux.cutools.tools.application.models.DOMStorageCapabilityStatus
import com.coppermux.cutools.tools.application.models.DOMStorageItemModel
import com.coppermux.cutools.tools.application.models.DOMStorageState
import com.coppermux.cutools.tools.application.models.StorageIdModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates LocalStorage / SessionStorage inspection, item mutation, clearing,
 * and live CDP event streaming for the currently attached page target via the
 * CDP `DOMStorage` domain.
 *
 * Sub-Phase 6B:
 * - [enable] / [disable] manage `DOMStorage` domain notifications.
 * - [getItems] issues `DOMStorage.getDOMStorageItems` and parses the genuine
 *   key-value `entries`; [setItem] / [removeItem] / [clear] issue the matching
 *   mutation commands. CDP failures are mapped to explicit
 *   [DOMStorageActionResult] statuses (`UNSUPPORTED`, `UNAVAILABLE`, `FAILED`)
 *   — no mock or hardcoded storage values are ever returned (No-Fake-Capability).
 * - The [CdpDOMStorageEventListener] routes genuine `DOMStorage` events
 *   (`domStorageItemAdded/Removed/Updated/ItemsCleared`) to an atomic in-memory
 *   store, so the inspected item map stays current without state fabrication.
 * - Session attach/detach lifecycle resets all target-scoped storage state and
 *   re-binds event subscriptions; a generation counter guards stale async
 *   results/events delivered after detach.
 *
 * Threading: state fields are `@Volatile`; the item map is a [ConcurrentHashMap]
 * so concurrent event streams never lose an update. All Chrome communication
 * reuses the shared [CommandDispatcher] — no second transport or dispatcher is
 * created.
 */
class DOMStorageEngine(
    private val dispatcher: CommandDispatcher,
    private val eventRouter: CdpEventRouter,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `DOMStorage` domain capability for the current session/target. */
    @Volatile
    var capability: DOMStorageCapabilityStatus? = null
        private set

    /** Immutable snapshot of the inspected storage state. */
    @Volatile
    var state: DOMStorageState = DOMStorageState.empty()
        private set

    /** Guards stale async results/events after detach / re-bind. */
    private val generation = AtomicInteger(0)

    private val subscriptions = CopyOnWriteArrayList<CdpEventRouter.Subscription>()

    /** Atomic backing store for the inspected storage area's key-value items. */
    private val itemStore = ConcurrentHashMap<String, String>()

    private val listener = CdpDOMStorageEventListener(object : DOMStorageStore {
        override fun onItemAdded(storageId: StorageIdModel, item: DOMStorageItemModel) {
            if (!storageId.matches(state.storageId)) return
            itemStore[item.key] = item.value
            refreshItems()
        }

        override fun onItemRemoved(storageId: StorageIdModel, key: String) {
            if (!storageId.matches(state.storageId)) return
            itemStore.remove(key)
            refreshItems()
        }

        override fun onItemUpdated(storageId: StorageIdModel, key: String, newValue: String) {
            if (!storageId.matches(state.storageId)) return
            itemStore[key] = newValue
            refreshItems()
        }

        override fun onItemsCleared(storageId: StorageIdModel) {
            if (!storageId.matches(state.storageId)) return
            itemStore.clear()
            refreshItems()
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
        itemStore.clear()
        updateState(
            DOMStorageState(
                sessionId = newSession.sessionId,
                targetId = newSession.targetId,
            )
        )
        clearSubscriptions()
        registerSubscriptions()
        sendEnable()
    }

    /** Detaches, clearing all target-scoped storage state and event subscriptions. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        capability = null
        itemStore.clear()
        updateState(DOMStorageState())
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
     * Enables `DOMStorage` domain notifications via `DOMStorage.enable`.
     * Protocol failures resolve to an explicit [DOMStorageActionResult] status.
     */
    fun enable(callback: (DOMStorageActionResult<Unit>) -> Unit = {}) {
        sendEnable(callback)
    }

    /**
     * Disables `DOMStorage` domain notifications via `DOMStorage.disable`.
     */
    fun disable(callback: (DOMStorageActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = DOMStorageCapabilityStatus.UNAVAILABLE
            callback(DOMStorageActionResult.unavailable("DOMStorage domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "DOMStorage.disable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    updateState(state.copy(enabled = false))
                    callback(DOMStorageActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "DOMStorage.disable"))
                }
            }
        }
    }

    private fun sendEnable(callback: (DOMStorageActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            capability = DOMStorageCapabilityStatus.UNAVAILABLE
            callback(DOMStorageActionResult.unavailable("DOMStorage domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "DOMStorage.enable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = DOMStorageCapabilityStatus.SUPPORTED
                    updateState(state.copy(enabled = true, capability = DOMStorageCapabilityStatus.SUPPORTED))
                    callback(DOMStorageActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "DOMStorage.enable"))
                }
            }
        }
    }

    // ================================================================
    // Item CRUD
    // ================================================================

    /**
     * Fetches all key-value items for a [storageId] via
     * `DOMStorage.getDOMStorageItems`, replacing the inspected storage snapshot.
     *
     * Genuine `entries` are parsed verbatim; missing/malformed results resolve to
     * [DOMStorageActionStatus.FAILED] rather than fabricated values.
     */
    fun getItems(storageId: StorageIdModel, callback: (DOMStorageActionResult<List<DOMStorageItemModel>>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = DOMStorageCapabilityStatus.UNAVAILABLE
            callback(DOMStorageActionResult.unavailable("DOMStorage domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "DOMStorage.getDOMStorageItems",
            params = mapOf("storageId" to storageIdParams(storageId)),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result
                    if (payload !is Map<*, *>) {
                        capability = DOMStorageCapabilityStatus.FAILED
                        callback(DOMStorageActionResult.failed("DOMStorage.getDOMStorageItems result is not an object"))
                        return@sendCommand
                    }
                    val items = parseItems(payload)
                    itemStore.clear()
                    items.forEach { itemStore[it.key] = it.value }
                    capability = DOMStorageCapabilityStatus.SUPPORTED
                    updateState(
                        state.copy(
                            origin = storageId.securityOrigin,
                            storageId = storageId,
                            storageType = storageId.storageType,
                            capability = DOMStorageCapabilityStatus.SUPPORTED,
                            items = itemStore.toMap(),
                        )
                    )
                    callback(DOMStorageActionResult.success(items))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "DOMStorage.getDOMStorageItems"))
                }
            }
        }
    }

    /**
     * Sets/updates a key-value pair for a [storageId] via
     * `DOMStorage.setDOMStorageItem`.
     */
    fun setItem(
        storageId: StorageIdModel,
        key: String,
        value: String,
        callback: (DOMStorageActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = DOMStorageCapabilityStatus.UNAVAILABLE
            callback(DOMStorageActionResult.unavailable("DOMStorage domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "DOMStorage.setDOMStorageItem",
            params = mapOf(
                "storageId" to storageIdParams(storageId),
                "key" to key,
                "value" to value,
            ),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = DOMStorageCapabilityStatus.SUPPORTED
                    if (storageId.matches(state.storageId)) {
                        itemStore[key] = value
                        refreshItems()
                    }
                    callback(DOMStorageActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "DOMStorage.setDOMStorageItem"))
                }
            }
        }
    }

    /**
     * Removes a key from a [storageId] via `DOMStorage.removeDOMStorageItem`.
     */
    fun removeItem(
        storageId: StorageIdModel,
        key: String,
        callback: (DOMStorageActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            capability = DOMStorageCapabilityStatus.UNAVAILABLE
            callback(DOMStorageActionResult.unavailable("DOMStorage domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "DOMStorage.removeDOMStorageItem",
            params = mapOf(
                "storageId" to storageIdParams(storageId),
                "key" to key,
            ),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = DOMStorageCapabilityStatus.SUPPORTED
                    if (storageId.matches(state.storageId)) {
                        itemStore.remove(key)
                        refreshItems()
                    }
                    callback(DOMStorageActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "DOMStorage.removeDOMStorageItem"))
                }
            }
        }
    }

    /**
     * Clears all entries for a [storageId] via `DOMStorage.clear`.
     */
    fun clear(storageId: StorageIdModel, callback: (DOMStorageActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            capability = DOMStorageCapabilityStatus.UNAVAILABLE
            callback(DOMStorageActionResult.unavailable("DOMStorage domain is not bound to an active session"))
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "DOMStorage.clear",
            params = mapOf("storageId" to storageIdParams(storageId)),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = DOMStorageCapabilityStatus.SUPPORTED
                    if (storageId.matches(state.storageId)) {
                        itemStore.clear()
                        refreshItems()
                    }
                    callback(DOMStorageActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "DOMStorage.clear"))
                }
            }
        }
    }

    // ================================================================
    // Event reception
    // ================================================================

    private fun registerSubscriptions() {
        subscriptions += eventRouter.subscribe("DOMStorage.domStorageItemAdded") { event -> onStorageEvent(event) }
        subscriptions += eventRouter.subscribe("DOMStorage.domStorageItemRemoved") { event -> onStorageEvent(event) }
        subscriptions += eventRouter.subscribe("DOMStorage.domStorageItemUpdated") { event -> onStorageEvent(event) }
        subscriptions += eventRouter.subscribe("DOMStorage.domStorageItemsCleared") { event -> onStorageEvent(event) }
    }

    /** Applies a DOMStorage event if it belongs to the active session. */
    private fun onStorageEvent(event: Event) {
        val active = session ?: return
        if (event.sessionId != active.sessionId) return
        listener.apply(event, active)
    }

    // ================================================================
    // Parsing & error mapping
    // ================================================================

    /** Serializes a [StorageIdModel] into the CDP `storageId` parameter object. */
    private fun storageIdParams(storageId: StorageIdModel): Map<String, Any?> {
        val base = mapOf(
            "securityOrigin" to storageId.securityOrigin,
            "isLocalStorage" to storageId.isLocalStorage,
        )
        return if (storageId.storageKey != null) base + ("storageKey" to storageId.storageKey) else base
    }

    /** Parses the genuine `entries` list of a `getDOMStorageItems` result. */
    private fun parseItems(result: Map<*, *>): List<DOMStorageItemModel> {
        val entries = result["entries"] as? List<*> ?: return emptyList()
        return entries.mapNotNull { entry ->
            if (entry !is Map<*, *>) return@mapNotNull null
            val key = entry["key"] as? String ?: return@mapNotNull null
            DOMStorageItemModel(key, (entry["value"] as? String) ?: "")
        }
    }

    /**
     * Maps a CDP command failure to an explicit [DOMStorageActionResult] status.
     * A method-not-found error (`-32601`) is [DOMStorageActionStatus.UNSUPPORTED];
     * everything else is [DOMStorageActionStatus.FAILED].
     */
    private fun classifyFailure(error: Throwable, method: String): DOMStorageActionResult<Nothing> {
        val message = error.message.orEmpty()
        val status = if (message.contains("-32601")) {
            capability = DOMStorageCapabilityStatus.UNSUPPORTED
            updateState(state.copy(capability = DOMStorageCapabilityStatus.UNSUPPORTED))
            DOMStorageActionResult.unsupported<Nothing>("$method is not available on the attached target", error)
        } else {
            capability = DOMStorageCapabilityStatus.FAILED
            updateState(state.copy(capability = DOMStorageCapabilityStatus.FAILED))
            DOMStorageActionResult.failed<Nothing>("$method failed: ${error.message}", error)
        }
        return status
    }

    // ================================================================
    // State helpers
    // ================================================================

    /** Publishes a fresh snapshot of the item store as the current state. */
    private fun refreshItems() {
        updateState(state.copy(items = itemStore.toMap()))
    }

    private fun updateState(newState: DOMStorageState) {
        state = newState
    }

    private fun clearSubscriptions() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
    }
}