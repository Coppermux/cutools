package com.coppermux.cutools.core.storage

import com.coppermux.cutools.core.error.ConnectionException
import com.coppermux.cutools.core.error.StorageException
import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.storage.models.IndexedDatabaseModel
import com.coppermux.cutools.core.storage.models.IndexedDbDataEntriesModel
import com.coppermux.cutools.core.storage.models.IndexedDbDataEntryModel
import com.coppermux.cutools.core.storage.models.IndexedObjectStoreIndexModel
import com.coppermux.cutools.core.storage.models.IndexedObjectStoreModel
import com.coppermux.cutools.core.storage.models.StorageIntrospectionActionResult
import com.coppermux.cutools.core.storage.models.StorageIntrospectionCapabilityStatus
import com.coppermux.cutools.core.storage.models.StorageIntrospectionError
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parses genuine `IndexedDB` domain wire payloads into the typed introspection
 * models without fabricating any state. Pure and stateless so it is trivially
 * unit-testable.
 */
object IndexedDbParser {

    /** Parses the genuine `databaseNames` list of a `requestDatabaseNames` result. */
    fun parseDatabaseNames(raw: Any?): List<String> {
        if (raw !is Map<*, *>) return emptyList()
        val names = raw["databaseNames"] as? List<*> ?: return emptyList()
        return names.mapNotNull { it as? String }
    }

    /** Parses the genuine `databaseWithObjectStores` object of a `requestDatabase` result. */
    fun parseDatabase(raw: Any?): IndexedDatabaseModel {
        if (raw !is Map<*, *>) return IndexedDatabaseModel()
        val db = raw["databaseWithObjectStores"] as? Map<*, *> ?: return IndexedDatabaseModel()
        val objectStores = (db["objectStores"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.mapNotNull { parseObjectStore(it) }
            ?: emptyList()
        return IndexedDatabaseModel(
            name = (db["name"] as? String) ?: "",
            version = (db["version"] as? Number)?.toInt() ?: 0,
            objectStores = objectStores,
        )
    }

    /**
     * Parses the genuine `objectStoreDataEntries` list (with `objectStoreData`
     * fallback) of a `requestData` result into an [IndexedDbDataEntriesModel].
     * The value JSON payloads are captured as raw strings and are never
     * deserialized, so oversized or malformed payloads cannot cause memory
     * overflow or invalid engine state.
     */
    fun parseData(raw: Any?): IndexedDbDataEntriesModel {
        if (raw !is Map<*, *>) return IndexedDbDataEntriesModel()
        val entries = (raw["objectStoreDataEntries"] as? List<*>)
            ?: raw["objectStoreData"] as? List<*> ?: emptyList()
        return IndexedDbDataEntriesModel(
            entries = entries.mapNotNull { it as? Map<*, *> }.mapNotNull { parseDataEntry(it) },
            hasMore = (raw["hasMore"] as? Boolean) ?: false,
        )
    }

    private fun parseObjectStore(store: Map<*, *>): IndexedObjectStoreModel? {
        val name = store["name"] as? String ?: return null
        val indexes = (store["indexes"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.mapNotNull { parseObjectStoreIndex(it) }
            ?: emptyList()
        return IndexedObjectStoreModel(
            name = name,
            keyPath = (store["keyPath"] as? String) ?: "",
            autoIncrement = (store["autoIncrement"] as? Boolean) ?: false,
            indexes = indexes,
        )
    }

    private fun parseObjectStoreIndex(index: Map<*, *>): IndexedObjectStoreIndexModel? {
        val name = index["name"] as? String ?: return null
        return IndexedObjectStoreIndexModel(
            name = name,
            keyPath = (index["keyPath"] as? String) ?: "",
            unique = (index["unique"] as? Boolean) ?: false,
            multiEntry = (index["multiEntry"] as? Boolean) ?: false,
        )
    }

    private fun parseDataEntry(entry: Map<*, *>): IndexedDbDataEntryModel? {
        val key = remoteObjectValue(entry["key"])
        val primaryKey = remoteObjectValue(entry["primaryKey"])
        val value = remoteObjectValue(entry["value"])
        if (key.isEmpty() && primaryKey.isEmpty() && value.isEmpty()) return null
        return IndexedDbDataEntryModel(key = key, primaryKey = primaryKey, value = value)
    }

    private fun remoteObjectValue(raw: Any?): String {
        if (raw !is Map<*, *>) return ""
        return (raw["value"] as? String) ?: ""
    }
}

/**
 * Orchestrates IndexedDB database listing, object store schema introspection,
 * entry querying, and object store clearing for the currently attached page
 * target via the CDP `IndexedDB` domain.
 *
 * Sub-Phase 8C — IndexedDB & CacheStorage Introspection Engine:
 * - [enable] / [disable] manage `IndexedDB` domain notifications.
 * - [requestDatabaseNames] issues `IndexedDB.requestDatabaseNames` for an origin.
 * - [requestDatabase] issues `IndexedDB.requestDatabase` and parses the genuine
 *   object store schema (name, version, key paths, indexes, autoIncrement)
 *   without assuming defaults.
 * - [requestData] issues `IndexedDB.requestData` with `skipCount` / `pageSize`
 *   key-range pagination and parses the genuine entry key, primary key, and JSON
 *   payload. Value payloads are parsed safely as strings — no deserialization of
 *   arbitrary JSON that could risk memory overflow or malformed state.
 * - [clearObjectStore] issues `IndexedDB.clearObjectStore`.
 * - Session attach/detach lifecycle resets all target-scoped state; a generation
 *   counter rejects stale async results delivered after detach.
 * - Protocol failures, missing sessions, unavailable methods, and connection
 *   loss each resolve to an explicit [StorageIntrospectionActionResult.Failure]
 *   carrying a [StorageIntrospectionError] (`Unsupported` / `Unavailable` /
 *   `ExecutionFailed` / `TargetDisconnected`) — No-Fake-Capability.
 *
 * Threading: state fields are `@Volatile`; all Chrome communication reuses the
 * shared [CommandDispatcher] — no second transport or dispatcher is created.
 */
class IndexedDbEngine(
    private val dispatcher: CommandDispatcher,
) {

    /** The active CDP session, or null when not bound. */
    @Volatile
    var session: SessionInfo? = null
        private set

    /** The `IndexedDB` domain capability for the current session/target. */
    @Volatile
    var capability: StorageIntrospectionCapabilityStatus? = null
        private set

    /** Guards stale async results after detach / re-bind. */
    private val generation = AtomicInteger(0)

    // ================================================================
    // Lifecycle
    // ================================================================

    /** Binds to an attached session and resets any prior target's state. */
    fun bindSession(newSession: SessionInfo) {
        generation.incrementAndGet()
        session = newSession
        capability = null
    }

    /** Detaches, clearing all target-scoped IndexedDB state. */
    fun detach() {
        generation.incrementAndGet()
        session = null
        capability = null
    }

    /** Handles connection loss — no commands are sent against the dead connection. */
    fun onConnectionLost() {
        detach()
    }

    // ================================================================
    // Domain lifecycle
    // ================================================================

    /** Enables `IndexedDB` domain notifications via `IndexedDB.enable`. */
    fun enable(callback: (StorageIntrospectionActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(method = "IndexedDB.enable", sessionId = sess.sessionId) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = StorageIntrospectionCapabilityStatus.SUPPORTED
                    callback(StorageIntrospectionActionResult.Success(Unit))
                }
                is CommandResult.Failure -> callback(classifyFailure(result.error, "IndexedDB.enable"))
            }
        }
    }

    /** Disables `IndexedDB` domain notifications via `IndexedDB.disable`. */
    fun disable(callback: (StorageIntrospectionActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(method = "IndexedDB.disable", sessionId = sess.sessionId) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> callback(StorageIntrospectionActionResult.Success(Unit))
                is CommandResult.Failure -> callback(classifyFailure(result.error, "IndexedDB.disable"))
            }
        }
    }

    // ================================================================
    // Inspection
    // ================================================================

    /**
     * Asynchronously fetches the IndexedDB database names for [origin] via
     * `IndexedDB.requestDatabaseNames`. Protocol failures resolve to an explicit
     * [StorageIntrospectionActionResult.Failure] — never to fabricated names.
     */
    fun requestDatabaseNames(origin: String, callback: (StorageIntrospectionActionResult<List<String>>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "IndexedDB.requestDatabaseNames",
            params = mapOf("securityOrigin" to origin),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result
                    if (payload !is Map<*, *>) {
                        capability = StorageIntrospectionCapabilityStatus.FAILED
                        callback(StorageIntrospectionActionResult.Failure(
                            StorageIntrospectionError.ExecutionFailed(
                                StorageException("IndexedDB.requestDatabaseNames result is not an object")
                            )
                        ))
                        return@sendCommand
                    }
                    capability = StorageIntrospectionCapabilityStatus.SUPPORTED
                    callback(StorageIntrospectionActionResult.Success(IndexedDbParser.parseDatabaseNames(payload)))
                }
                is CommandResult.Failure -> callback(classifyFailure(result.error, "IndexedDB.requestDatabaseNames"))
            }
        }
    }

    /**
     * Asynchronously fetches the schema of [databaseName] within [origin] via
     * `IndexedDB.requestDatabase`, parsing the genuine object stores, key paths,
     * indexes, and autoIncrement flags.
     */
    fun requestDatabase(
        origin: String,
        databaseName: String,
        callback: (StorageIntrospectionActionResult<IndexedDatabaseModel>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "IndexedDB.requestDatabase",
            params = mapOf("securityOrigin" to origin, "databaseName" to databaseName),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result
                    if (payload !is Map<*, *>) {
                        capability = StorageIntrospectionCapabilityStatus.FAILED
                        callback(StorageIntrospectionActionResult.Failure(
                            StorageIntrospectionError.ExecutionFailed(
                                StorageException("IndexedDB.requestDatabase result is not an object")
                            )
                        ))
                        return@sendCommand
                    }
                    capability = StorageIntrospectionCapabilityStatus.SUPPORTED
                    callback(StorageIntrospectionActionResult.Success(IndexedDbParser.parseDatabase(payload)))
                }
                is CommandResult.Failure -> callback(classifyFailure(result.error, "IndexedDB.requestDatabase"))
            }
        }
    }

    /**
     * Asynchronously queries a key-range page of entries from [objectStoreName]
     * via `IndexedDB.requestData` with [skipCount] / [pageSize] pagination.
     */
    fun requestData(
        origin: String,
        databaseName: String,
        objectStoreName: String,
        skipCount: Int,
        pageSize: Int,
        callback: (StorageIntrospectionActionResult<IndexedDbDataEntriesModel>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "IndexedDB.requestData",
            params = mapOf(
                "securityOrigin" to origin,
                "databaseName" to databaseName,
                "objectStoreName" to objectStoreName,
                "skipCount" to skipCount,
                "pageSize" to pageSize,
            ),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result
                    if (payload !is Map<*, *>) {
                        capability = StorageIntrospectionCapabilityStatus.FAILED
                        callback(StorageIntrospectionActionResult.Failure(
                            StorageIntrospectionError.ExecutionFailed(
                                StorageException("IndexedDB.requestData result is not an object")
                            )
                        ))
                        return@sendCommand
                    }
                    capability = StorageIntrospectionCapabilityStatus.SUPPORTED
                    callback(StorageIntrospectionActionResult.Success(IndexedDbParser.parseData(payload)))
                }
                is CommandResult.Failure -> callback(classifyFailure(result.error, "IndexedDB.requestData"))
            }
        }
    }

    // ================================================================
    // Mutation
    // ================================================================

    /**
     * Asynchronously clears all entries from [objectStoreName] via
     * `IndexedDB.clearObjectStore`.
     */
    fun clearObjectStore(
        origin: String,
        databaseName: String,
        objectStoreName: String,
        callback: (StorageIntrospectionActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "IndexedDB.clearObjectStore",
            params = mapOf(
                "securityOrigin" to origin,
                "databaseName" to databaseName,
                "objectStoreName" to objectStoreName,
            ),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = StorageIntrospectionCapabilityStatus.SUPPORTED
                    callback(StorageIntrospectionActionResult.Success(Unit))
                }
                is CommandResult.Failure -> callback(classifyFailure(result.error, "IndexedDB.clearObjectStore"))
            }
        }
    }

    // ================================================================
    // Error mapping & state helpers
    // ================================================================

    private fun unavailable(): StorageIntrospectionActionResult<Nothing> {
        capability = StorageIntrospectionCapabilityStatus.UNAVAILABLE
        return StorageIntrospectionActionResult.Failure(
            StorageIntrospectionError.Unavailable("IndexedDB domain is not bound to an active session")
        )
    }

    /**
     * Maps a CDP command failure to an explicit [StorageIntrospectionError]. A
     * method-not-found error (`-32601`) is [StorageIntrospectionError.Unsupported];
     * a connection failure is [StorageIntrospectionError.TargetDisconnected];
     * everything else is [StorageIntrospectionError.ExecutionFailed].
     */
    private fun classifyFailure(error: Throwable, method: String): StorageIntrospectionActionResult<Nothing> {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = StorageIntrospectionCapabilityStatus.UNSUPPORTED
            return StorageIntrospectionActionResult.Failure(
                StorageIntrospectionError.Unsupported("$method is not available on the attached target")
            )
        }
        if (error is ConnectionException) {
            capability = StorageIntrospectionCapabilityStatus.UNAVAILABLE
            return StorageIntrospectionActionResult.Failure(StorageIntrospectionError.TargetDisconnected())
        }
        capability = StorageIntrospectionCapabilityStatus.FAILED
        return StorageIntrospectionActionResult.Failure(StorageIntrospectionError.ExecutionFailed(StorageException("$method failed: ${error.message}", cause = error)))
    }
}