package com.coppermux.cutools.tools.application.engine

import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.tools.application.models.DOMStorageItemModel
import com.coppermux.cutools.tools.application.models.StorageIdModel

/**
 * Applies genuine Chrome `DOMStorage` domain events to a [DOMStorageStore]
 * without fabricating any state.
 *
 * Handled events:
 *  - `DOMStorage.domStorageItemAdded`    → [DOMStorageStore.onItemAdded]
 *  - `DOMStorage.domStorageItemRemoved`  → [DOMStorageStore.onItemRemoved]
 *  - `DOMStorage.domStorageItemUpdated`  → [DOMStorageStore.onItemUpdated]
 *  - `DOMStorage.domStorageItemsCleared` → [DOMStorageStore.onItemsCleared]
 *
 * Only Chrome-supplied fields are populated; malformed payloads (non-map params,
 * missing storageId/key) are tolerated and return without mutating the store, so
 * the event pipeline never crashes.
 */
class CdpDOMStorageEventListener(private val store: DOMStorageStore) {

    /**
     * Applies a classified DOMStorage event. Events for other sessions must be
     * filtered by the caller before reaching here.
     */
    fun apply(event: Event, session: SessionInfo) {
        if (event.params !is Map<*, *>) return
        when (event.method) {
            "DOMStorage.domStorageItemAdded" -> applyItemAdded(event.params)
            "DOMStorage.domStorageItemRemoved" -> applyItemRemoved(event.params)
            "DOMStorage.domStorageItemUpdated" -> applyItemUpdated(event.params)
            "DOMStorage.domStorageItemsCleared" -> applyItemsCleared(event.params)
        }
    }

    private fun applyItemAdded(params: Map<*, *>) {
        val storageId = parseStorageId(params["storageId"]) ?: return
        val key = params["key"] as? String ?: return
        store.onItemAdded(storageId, DOMStorageItemModel(key, (params["newValue"] as? String) ?: ""))
    }

    private fun applyItemRemoved(params: Map<*, *>) {
        val storageId = parseStorageId(params["storageId"]) ?: return
        val key = params["key"] as? String ?: return
        store.onItemRemoved(storageId, key)
    }

    private fun applyItemUpdated(params: Map<*, *>) {
        val storageId = parseStorageId(params["storageId"]) ?: return
        val key = params["key"] as? String ?: return
        store.onItemUpdated(storageId, key, (params["newValue"] as? String) ?: "")
    }

    private fun applyItemsCleared(params: Map<*, *>) {
        val storageId = parseStorageId(params["storageId"]) ?: return
        store.onItemsCleared(storageId)
    }

    companion object {
        /**
         * Parses a CDP `StorageId` from an event `storageId` payload, or null
         * when the payload is malformed. `isLocalStorage` defaults to false
         * (session storage) when absent — no storage kind is ever invented.
         */
        fun parseStorageId(raw: Any?): StorageIdModel? {
            if (raw !is Map<*, *>) return null
            val securityOrigin = raw["securityOrigin"] as? String ?: return null
            val isLocalStorage = (raw["isLocalStorage"] as? Boolean) ?: false
            val storageKey = raw["storageKey"] as? String
            return StorageIdModel(securityOrigin, isLocalStorage, storageKey)
        }
    }
}

/**
 * Receives parsed `DOMStorage` events. Implementations must apply updates
 * atomically so concurrent event streams never lose an update.
 */
interface DOMStorageStore {
    fun onItemAdded(storageId: StorageIdModel, item: DOMStorageItemModel)
    fun onItemRemoved(storageId: StorageIdModel, key: String)
    fun onItemUpdated(storageId: StorageIdModel, key: String, newValue: String)
    fun onItemsCleared(storageId: StorageIdModel)
}