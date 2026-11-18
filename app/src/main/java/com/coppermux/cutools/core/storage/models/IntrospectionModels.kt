package com.coppermux.cutools.core.storage.models

/**
 * Schema of a single IndexedDB object store, parsed strictly from a genuine CDP
 * `IndexedDB.requestDatabase` `ObjectStore` entry. [keyPath] is the object
 * store's key path and [autoIncrement] the genuine Chrome flag; [indexes] holds
 * the secondary index definitions. No key path or index is ever fabricated.
 */
data class IndexedObjectStoreModel(
    val name: String = "",
    val keyPath: String = "",
    val autoIncrement: Boolean = false,
    val indexes: List<IndexedObjectStoreIndexModel> = emptyList(),
)

/**
 * Schema of a single IndexedDB object-store index, parsed strictly from a
 * genuine CDP `ObjectStoreIndex` entry. [keyPath] is the index key path and
 * [unique] / [multiEntry] mirror the genuine Chrome flags.
 */
data class IndexedObjectStoreIndexModel(
    val name: String = "",
    val keyPath: String = "",
    val unique: Boolean = false,
    val multiEntry: Boolean = false,
)

/**
 * A single IndexedDB database, parsed strictly from a genuine CDP
 * `IndexedDB.requestDatabase` `DatabaseWithObjectStores` result. [name] /
 * [version] are the database metadata reported by Chrome and [objectStores]
 * holds the genuine object store schema. No database, store, or index is ever
 * fabricated (No-Fake-Capability rule).
 */
data class IndexedDatabaseModel(
    val name: String = "",
    val version: Int = 0,
    val objectStores: List<IndexedObjectStoreModel> = emptyList(),
)

/**
 * A single IndexedDB entry/document within an object store, parsed strictly from
 * a genuine CDP `IndexedDB.requestData` data entry. [key] and [primaryKey] are
 * the entry's keys and [value] is the raw JSON payload of the stored value —
 * parsed safely as a string so malformed or oversized payloads never risk memory
 * overflow or invalid state.
 */
data class IndexedDbDataEntryModel(
    val key: String = "",
    val primaryKey: String = "",
    val value: String = "",
)

/**
 * A key-range paginated page of object store entries, parsed strictly from a
 * genuine CDP `IndexedDB.requestData` result. [entries] holds the page's
 * genuine data entries and [hasMore] mirrors Chrome's pagination signal (when
 * present). No entry is ever fabricated.
 */
data class IndexedDbDataEntriesModel(
    val entries: List<IndexedDbDataEntryModel> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * A single browser cache storage bucket, parsed strictly from a genuine CDP
 * `CacheStorage.requestCacheNames` `caches` entry. [cacheId] is the opaque cache
 * bucket identifier, [securityOrigin] the bucket's origin, and [cacheName] the
 * bucket name as reported by Chrome. No bucket is ever fabricated.
 */
data class CacheStorageBucketModel(
    val cacheId: String = "",
    val securityOrigin: String = "",
    val cacheName: String = "",
)

/**
 * A single cached request/response pair, parsed strictly from a genuine CDP
 * `CacheStorage.requestEntries` `DataEntry`. [requestURL] / [requestHeaders]
 * describe the cached request and [responseStatus] / [responseStatusText] /
 * [responseHeaders] the cached response as reported by Chrome. No entry data is
 * ever fabricated.
 */
data class CacheEntryModel(
    val requestURL: String = "",
    val requestHeaders: List<String> = emptyList(),
    val responseStatus: Int = 0,
    val responseStatusText: String = "",
    val responseHeaders: List<String> = emptyList(),
)

/**
 * Availability of the `IndexedDB` / `CacheStorage` CDP domains for the bound
 * target. No capability is ever fabricated: a genuine domain failure or an
 * unavailable method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED]
 * explicitly (No-Fake-Capability rule).
 */
enum class StorageIntrospectionCapabilityStatus {
    /** `IndexedDB` / `CacheStorage` commands are functional on the attached target. */
    SUPPORTED,

    /** An `IndexedDB` / `CacheStorage` method is not available on the connected target. */
    UNSUPPORTED,

    /** The domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** An `IndexedDB` / `CacheStorage` command failed with an error. */
    FAILED,
}

/**
 * Outcome of an `IndexedDB` / `CacheStorage` storage introspection operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with a
 * value or resolves to an explicit [StorageIntrospectionError] (`Unsupported`,
 * `Unavailable`, `ExecutionFailed`, `TargetDisconnected`). No mock or hardcoded
 * database / cache data is ever produced (No-Fake-Capability rule).
 *
 * @param T The value carried on success (e.g. `IndexedDatabaseModel` or `Unit`).
 */
sealed interface StorageIntrospectionActionResult<out T> {

    /** The storage introspection operation succeeded, carrying [value]. */
    data class Success<T>(val value: T) : StorageIntrospectionActionResult<T>

    /** The storage introspection operation failed, carrying an explicit [error]. */
    data class Failure(val error: StorageIntrospectionError) : StorageIntrospectionActionResult<Nothing>
}

/**
 * Explicit, non-fabricated error for a failed storage introspection operation.
 */
sealed interface StorageIntrospectionError {

    /** An `IndexedDB` / `CacheStorage` method is not available on the attached target. */
    data class Unsupported(val reason: String) : StorageIntrospectionError

    /** The `IndexedDB` / `CacheStorage` domain cannot be reached (e.g., no bound session). */
    data class Unavailable(val detail: String) : StorageIntrospectionError

    /** The introspection command failed (protocol error, parsing error, ...). */
    data class ExecutionFailed(val cause: Throwable) : StorageIntrospectionError

    /** The attached target disconnected while the operation was in flight. */
    class TargetDisconnected : StorageIntrospectionError
}