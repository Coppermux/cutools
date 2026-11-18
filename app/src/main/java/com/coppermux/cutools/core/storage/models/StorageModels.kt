package com.coppermux.cutools.core.storage.models

/**
 * Storage type identifiers defined by the CDP `Storage` domain.
 *
 * Each constant carries the wire value Chrome expects in the
 * `Storage.getUsageAndQuota` `usageBreakdown` entries and as the comma-separated
 * `storageTypes` list of `Storage.clearDataForOrigin`.
 *
 * Values are mapped strictly from Chrome; an unrecognized or absent wire value
 * resolves to `null` — no storage type is ever assumed (Exact Mapping rule).
 */
enum class StorageTypeModel(val wireValue: String) {
    APPCACHE("appcache"),
    COOKIES("cookies"),
    FILE_SYSTEMS("file_systems"),
    INDEXEDDB("indexeddb"),
    LOCAL_STORAGE("local_storage"),
    SHADER_CACHE("shader_cache"),
    WEBSQL("websql"),
    SERVICE_WORKERS("service_workers"),
    CACHE_STORAGE("cache_storage"),
    INTEREST_GROUPS("interest_groups"),
    ALL("all");

    companion object {
        /** Maps a CDP `StorageType` wire string to a [StorageTypeModel], or null. */
        fun fromWire(value: String?): StorageTypeModel? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Storage usage (bytes) for a single [StorageTypeModel], parsed from a genuine
 * `Storage.getUsageAndQuota` `usageBreakdown` entry.
 *
 * [storageType] is null when Chrome reports an unrecognized storage type; no
 * type is fabricated (No-Fake-Capability rule).
 */
data class UsageForTypeModel(
    val storageType: StorageTypeModel? = null,
    val usageBytes: Long = 0L,
)

/**
 * Origin-level storage usage and quota, parsed from a genuine
 * `Storage.getUsageAndQuota` response.
 *
 * [usage] / [quota] are the total bytes reported by Chrome, and
 * [usageBreakdown] holds the per-type usage breakdown as reported by the
 * browser. All numbers originate from Chrome — none are fabricated.
 */
data class StorageUsageAndQuotaModel(
    val usage: Long,
    val quota: Long,
    val usageBreakdown: List<UsageForTypeModel> = emptyList(),
)

/**
 * Identifies a single origin cache storage bucket within Chrome's storage
 * cache, parsed from a genuine `Storage.cacheStorageContentUpdated` `key`.
 *
 * [id] is the opaque cache bucket identifier and [storage] the cache partition
 * path reported by Chrome.
 */
data class CacheStorageKeyModel(
    val id: String = "",
    val storage: String = "",
)

/**
 * A single cache storage entry payload observed in a genuine
 * `Storage.cacheStorageContentUpdated` event.
 *
 * [requestUrl] / [requestMethod] describe the cached request, and
 * [responseHeaderNames] the names of the cached response headers as reported by
 * Chrome. No entry data is ever fabricated.
 */
data class CacheStorageContentModel(
    val requestUrl: String = "",
    val requestMethod: String = "",
    val responseHeaderNames: List<String> = emptyList(),
)

/**
 * A genuine `Storage.cacheStorageContentUpdated` event, captured for an origin
 * that is being tracked via [com.coppermux.cutools.core.storage.StorageEngine.trackCacheStorageForOrigin].
 *
 * [origin] identifies the affected origin, [key] the affected cache bucket, and
 * [content] the optional entry payload Chrome supplied.
 */
data class CacheStorageContentUpdatedModel(
    val origin: String = "",
    val key: CacheStorageKeyModel = CacheStorageKeyModel(),
    val content: CacheStorageContentModel? = null,
)

/**
 * A genuine `Storage.cacheStorageListUpdated` event, captured for an origin that
 * is being tracked via [com.coppermux.cutools.core.storage.StorageEngine.trackCacheStorageForOrigin].
 * [origin] identifies the affected origin.
 */
data class CacheStorageListUpdatedModel(
    val origin: String = "",
)

/**
 * Availability of the `Storage` CDP domain for the bound target/origin.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class StorageCapabilityStatus {
    /** `Storage` commands are functional on the attached target. */
    SUPPORTED,

    /** A `Storage` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `Storage` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** A `Storage` command failed with an error. */
    FAILED,
}

/**
 * Outcome of a `Storage` operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with a
 * value or resolves to an explicit [StorageError] (`Unsupported`,
 * `Unavailable`, `ExecutionFailed`, `TargetDisconnected`). No mock or hardcoded
 * storage / cache data is ever produced (No-Fake-Capability rule).
 *
 * @param T The value carried on success (e.g. `StorageUsageAndQuotaModel` or `Unit`).
 */
sealed interface StorageActionResult<out T> {

    /** The storage operation succeeded, carrying [value]. */
    data class Success<T>(val value: T) : StorageActionResult<T>

    /** The storage operation failed, carrying an explicit [error]. */
    data class Failure(val error: StorageError) : StorageActionResult<Nothing>
}

/**
 * Explicit, non-fabricated error for a failed `Storage` operation.
 */
sealed interface StorageError {

    /** A `Storage` method is not available on the attached target. */
    data class Unsupported(val reason: String) : StorageError

    /** The `Storage` domain cannot be reached (e.g., no bound session). */
    data class Unavailable(val detail: String) : StorageError

    /** The storage command failed (protocol error, parsing error, ...). */
    data class ExecutionFailed(val cause: Throwable) : StorageError

    /** The attached target disconnected while the operation was in flight. */
    class TargetDisconnected : StorageError
}