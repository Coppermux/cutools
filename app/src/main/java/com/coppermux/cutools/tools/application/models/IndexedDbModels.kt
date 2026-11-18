package com.coppermux.cutools.tools.application.models

/**
 * A single IndexedDB database, parsed strictly from a genuine CDP
 * `IndexedDB.requestDatabase` `databaseWithObjectStores` result.
 *
 * [name] / [version] are the database metadata reported by Chrome and
 * [objectStores] holds the genuine object store schema. No database, store, or
 * index is ever fabricated (No-Fake-Capability rule).
 */
data class IndexedDbDatabaseModel(
    val name: String = "",
    val version: Int = 0,
    val objectStores: List<IndexedDbObjectStoreModel> = emptyList(),
)

/**
 * Schema of a single IndexedDB object store, parsed strictly from a genuine CDP
 * `ObjectStore` entry. [keyPath] is the index key path string and
 * [autoIncrement] the genuine Chrome flag; [indexes] holds the secondary index
 * definitions. No key path or index is ever fabricated.
 */
data class IndexedDbObjectStoreModel(
    val name: String = "",
    val keyPath: String = "",
    val autoIncrement: Boolean = false,
    val indexes: List<IndexedDbIndexModel> = emptyList(),
)

/**
 * Schema of a single IndexedDB object-store index, parsed strictly from a
 * genuine CDP `ObjectStoreIndex` entry. [keyPath] is the index key path and
 * [unique] / [multiEntry] mirror the genuine Chrome flags.
 */
data class IndexedDbIndexModel(
    val name: String = "",
    val keyPath: String = "",
    val unique: Boolean = false,
    val multiEntry: Boolean = false,
)

/**
 * A single IndexedDB entry/document within an object store, parsed strictly from
 * a genuine CDP `IndexedDB.requestData` `objectStoreData` entry.
 *
 * [key] and [primaryKey] are the entry's keys and [value] is the raw JSON
 * payload of the stored value — parsed safely as a string so malformed or
 * oversized payloads never risk memory overflow or invalid state.
 */
data class IndexedDbDataEntryModel(
    val key: String = "",
    val primaryKey: String = "",
    val value: String = "",
)

/**
 * Availability of the `IndexedDB` CDP domain for the bound target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class IndexedDbCapabilityStatus {
    /** `IndexedDB` commands/notifications are functional on the attached target. */
    SUPPORTED,

    /** An `IndexedDB` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `IndexedDB` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** An `IndexedDB` command failed with an error. */
    FAILED,
}

/** Result status for an [IndexedDbActionResult]. */
enum class IndexedDbActionStatus {
    SUCCESS,
    UNSUPPORTED,
    UNAVAILABLE,
    FAILED,
}

/**
 * Outcome of an `IndexedDB` operation.
 *
 * Mirrors the CDP error mapping contract: an operation either succeeds or
 * resolves to an explicit [IndexedDbActionStatus] (`UNSUPPORTED`, `UNAVAILABLE`,
 * `FAILED`) with an optional diagnostic message and throwable. No mock or
 * hardcoded database/schema values are ever returned.
 *
 * @param T The value carried on success (e.g. [IndexedDbDatabaseModel] or `Unit`).
 */
data class IndexedDbActionResult<out T>(
    val status: IndexedDbActionStatus,
    val value: T? = null,
    val message: String? = null,
    val throwable: Throwable? = null,
) {
    val isSuccess: Boolean get() = status == IndexedDbActionStatus.SUCCESS

    companion object {
        fun <T> success(value: T): IndexedDbActionResult<T> =
            IndexedDbActionResult(IndexedDbActionStatus.SUCCESS, value = value)

        fun <T> unsupported(message: String?, throwable: Throwable? = null): IndexedDbActionResult<T> =
            IndexedDbActionResult(IndexedDbActionStatus.UNSUPPORTED, message = message, throwable = throwable)

        fun <T> unavailable(message: String?, throwable: Throwable? = null): IndexedDbActionResult<T> =
            IndexedDbActionResult(IndexedDbActionStatus.UNAVAILABLE, message = message, throwable = throwable)

        fun <T> failed(message: String?, throwable: Throwable? = null): IndexedDbActionResult<T> =
            IndexedDbActionResult(IndexedDbActionStatus.FAILED, message = message, throwable = throwable)
    }
}