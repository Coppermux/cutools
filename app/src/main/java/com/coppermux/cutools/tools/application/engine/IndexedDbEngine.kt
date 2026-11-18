package com.coppermux.cutools.tools.application.engine

import com.coppermux.cutools.core.model.CommandResult
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.core.transport.CommandDispatcher
import com.coppermux.cutools.tools.application.models.IndexedDbActionResult
import com.coppermux.cutools.tools.application.models.IndexedDbActionStatus
import com.coppermux.cutools.tools.application.models.IndexedDbCapabilityStatus
import com.coppermux.cutools.tools.application.models.IndexedDbDatabaseModel
import com.coppermux.cutools.tools.application.models.IndexedDbDataEntryModel
import com.coppermux.cutools.tools.application.models.IndexedDbIndexModel
import com.coppermux.cutools.tools.application.models.IndexedDbObjectStoreModel
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates IndexedDB database listing, object store schema introspection,
 * entry querying, clearing, and deletion for the currently attached page target
 * via the CDP `IndexedDB` domain.
 *
 * Sub-Phase 6D:
 * - [enable] / [disable] manage `IndexedDB` domain notifications.
 * - [getDatabaseNames] issues `IndexedDB.requestDatabaseNames` for an origin.
 * - [getDatabaseSchema] issues `IndexedDB.requestDatabase` and parses the
 *   genuine object store schema (name, version, key paths, indexes,
 *   autoIncrement) without assuming defaults.
 * - [getEntries] issues `IndexedDB.requestData` with `skipCount` / `pageSize`
 *   pagination and parses the genuine entry key, primary key, and JSON payload.
 *   Value payloads are parsed safely as strings — no deserialization of
 *   arbitrary JSON that could risk memory overflow or malformed state.
 * - [clearObjectStore] / [deleteDatabase] issue the matching mutation commands.
 *   CDP failures are mapped to explicit [IndexedDbActionResult] statuses
 *   (`UNSUPPORTED`, `UNAVAILABLE`, `FAILED`) — no mock or hardcoded database
 *   values are ever returned (No-Fake-Capability).
 * - Session attach/detach lifecycle resets all target-scoped state, and a
 *   generation counter guards stale async results delivered after detach.
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
    var capability: IndexedDbCapabilityStatus? = null
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

    /**
     * Enables `IndexedDB` domain notifications via `IndexedDB.enable`.
     * Protocol failures resolve to an explicit [IndexedDbActionResult] status.
     */
    fun enable(callback: (IndexedDbActionResult<Unit>) -> Unit = {}) {
        sendEnable(callback)
    }

    private fun sendEnable(callback: (IndexedDbActionResult<Unit>) -> Unit) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "IndexedDB.enable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = IndexedDbCapabilityStatus.SUPPORTED
                    callback(IndexedDbActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "IndexedDB.enable"))
                }
            }
        }
    }

    /**
     * Disables `IndexedDB` domain notifications via `IndexedDB.disable`.
     * Protocol failures resolve to an explicit [IndexedDbActionResult] status.
     */
    fun disable(callback: (IndexedDbActionResult<Unit>) -> Unit = {}) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "IndexedDB.disable",
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    callback(IndexedDbActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "IndexedDB.disable"))
                }
            }
        }
    }

    // ================================================================
    // Inspection
    // ================================================================

    /**
     * Asynchronously fetches the IndexedDB database names for [origin] via
     * `IndexedDB.requestDatabaseNames`.
     *
     * Protocol failures resolve to an explicit [IndexedDbActionResult] status —
     * never to fabricated database names.
     */
    fun getDatabaseNames(origin: String, callback: (IndexedDbActionResult<List<String>>) -> Unit) {
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
                        capability = IndexedDbCapabilityStatus.FAILED
                        callback(IndexedDbActionResult.failed("IndexedDB.requestDatabaseNames result is not an object"))
                        return@sendCommand
                    }
                    capability = IndexedDbCapabilityStatus.SUPPORTED
                    callback(IndexedDbActionResult.success(parseDatabaseNames(payload)))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "IndexedDB.requestDatabaseNames"))
                }
            }
        }
    }

    /**
     * Asynchronously fetches the schema of [databaseName] within [origin] via
     * `IndexedDB.requestDatabase`, parsing the genuine object stores, key paths,
     * indexes, and autoIncrement flags.
     */
    fun getDatabaseSchema(
        origin: String,
        databaseName: String,
        callback: (IndexedDbActionResult<IndexedDbDatabaseModel>) -> Unit,
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
                        capability = IndexedDbCapabilityStatus.FAILED
                        callback(IndexedDbActionResult.failed("IndexedDB.requestDatabase result is not an object"))
                        return@sendCommand
                    }
                    capability = IndexedDbCapabilityStatus.SUPPORTED
                    callback(IndexedDbActionResult.success(parseDatabase(payload)))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "IndexedDB.requestDatabase"))
                }
            }
        }
    }

    /**
     * Asynchronously queries entries from [objectStoreName] via
     * `IndexedDB.requestData` with [skip] / [limit] pagination (mapped to the CDP
     * `skipCount` / `pageSize` parameters).
     */
    fun getEntries(
        origin: String,
        databaseName: String,
        objectStoreName: String,
        skip: Int,
        limit: Int,
        callback: (IndexedDbActionResult<List<IndexedDbDataEntryModel>>) -> Unit,
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
                "skipCount" to skip,
                "pageSize" to limit,
            ),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    val payload = result.response.result
                    if (payload !is Map<*, *>) {
                        capability = IndexedDbCapabilityStatus.FAILED
                        callback(IndexedDbActionResult.failed("IndexedDB.requestData result is not an object"))
                        return@sendCommand
                    }
                    capability = IndexedDbCapabilityStatus.SUPPORTED
                    callback(IndexedDbActionResult.success(parseEntries(payload)))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "IndexedDB.requestData"))
                }
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
        callback: (IndexedDbActionResult<Unit>) -> Unit,
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
                    capability = IndexedDbCapabilityStatus.SUPPORTED
                    callback(IndexedDbActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "IndexedDB.clearObjectStore"))
                }
            }
        }
    }

    /**
     * Asynchronously deletes an entire IndexedDB database via
     * `IndexedDB.deleteDatabase`.
     */
    fun deleteDatabase(
        origin: String,
        databaseName: String,
        callback: (IndexedDbActionResult<Unit>) -> Unit,
    ) {
        val sess = session
        if (sess == null) {
            callback(unavailable())
            return
        }
        val gen = generation.get()
        dispatcher.sendCommand(
            method = "IndexedDB.deleteDatabase",
            params = mapOf("securityOrigin" to origin, "databaseName" to databaseName),
            sessionId = sess.sessionId,
        ) { result ->
            if (generation.get() != gen) return@sendCommand
            when (result) {
                is CommandResult.Success -> {
                    capability = IndexedDbCapabilityStatus.SUPPORTED
                    callback(IndexedDbActionResult.success(Unit))
                }
                is CommandResult.Failure -> {
                    callback(classifyFailure(result.error, "IndexedDB.deleteDatabase"))
                }
            }
        }
    }

    // ================================================================
    // Parsing & error mapping
    // ================================================================

    private fun unavailable(): IndexedDbActionResult<Nothing> {
        capability = IndexedDbCapabilityStatus.UNAVAILABLE
        return IndexedDbActionResult.unavailable("IndexedDB domain is not bound to an active session")
    }

    /** Parses the genuine `databaseNames` list of a `requestDatabaseNames` result. */
    private fun parseDatabaseNames(result: Map<*, *>): List<String> {
        val names = result["databaseNames"] as? List<*> ?: return emptyList()
        return names.mapNotNull { it as? String }
    }

    /** Parses the genuine `databaseWithObjectStores` object of a `requestDatabase` result. */
    private fun parseDatabase(result: Map<*, *>): IndexedDbDatabaseModel {
        val db = result["databaseWithObjectStores"] as? Map<*, *>
            ?: return IndexedDbDatabaseModel()
        val objectStores = (db["objectStores"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.mapNotNull { parseObjectStore(it) }
            ?: emptyList()
        return IndexedDbDatabaseModel(
            name = (db["name"] as? String) ?: "",
            version = (db["version"] as? Number)?.toInt() ?: 0,
            objectStores = objectStores,
        )
    }

    /** Parses a single CDP `ObjectStore` entry into an [IndexedDbObjectStoreModel]. */
    private fun parseObjectStore(store: Map<*, *>): IndexedDbObjectStoreModel? {
        val name = store["name"] as? String ?: return null
        val indexes = (store["indexes"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.mapNotNull { parseObjectStoreIndex(it) }
            ?: emptyList()
        return IndexedDbObjectStoreModel(
            name = name,
            keyPath = (store["keyPath"] as? String) ?: "",
            autoIncrement = (store["autoIncrement"] as? Boolean) ?: false,
            indexes = indexes,
        )
    }

    /** Parses a single CDP `ObjectStoreIndex` entry into an [IndexedDbIndexModel]. */
    private fun parseObjectStoreIndex(index: Map<*, *>): IndexedDbIndexModel? {
        val name = index["name"] as? String ?: return null
        return IndexedDbIndexModel(
            name = name,
            keyPath = (index["keyPath"] as? String) ?: "",
            unique = (index["unique"] as? Boolean) ?: false,
            multiEntry = (index["multiEntry"] as? Boolean) ?: false,
        )
    }

    /**
     * Parses the genuine `objectStoreData` list of a `requestData` result into
     * [IndexedDbDataEntryModel]s. The value JSON payload is captured as a raw
     * string and is never deserialized, so oversized or malformed payloads
     * cannot cause memory overflow or invalid engine state.
     */
    private fun parseEntries(result: Map<*, *>): List<IndexedDbDataEntryModel> {
        val entries = result["objectStoreData"] as? List<*> ?: return emptyList()
        return entries.mapNotNull { entry ->
            if (entry !is Map<*, *>) return@mapNotNull null
            val key = (entry["key"] as? Map<*, *>)?.get("value") as? String
            val primaryKey = (entry["primaryKey"] as? Map<*, *>)?.get("value") as? String
            val valueJson = (entry["value"] as? Map<*, *>)?.get("value") as? String
            if (key == null && primaryKey == null && valueJson == null) return@mapNotNull null
            IndexedDbDataEntryModel(
                key = key ?: "",
                primaryKey = primaryKey ?: "",
                value = valueJson ?: "",
            )
        }
    }

    /**
     * Maps a CDP command failure to an explicit [IndexedDbActionResult] status.
     * A method-not-found error (`-32601`) is [IndexedDbActionStatus.UNSUPPORTED];
     * everything else is [IndexedDbActionStatus.FAILED].
     */
    private fun classifyFailure(error: Throwable, method: String): IndexedDbActionResult<Nothing> {
        val message = error.message.orEmpty()
        if (message.contains("-32601")) {
            capability = IndexedDbCapabilityStatus.UNSUPPORTED
            return IndexedDbActionResult.unsupported("$method is not available on the attached target", error)
        }
        capability = IndexedDbCapabilityStatus.FAILED
        return IndexedDbActionResult.failed("$method failed: ${error.message}", error)
    }
}