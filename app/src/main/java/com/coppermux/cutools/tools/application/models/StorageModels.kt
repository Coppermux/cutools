package com.coppermux.cutools.tools.application.models

/**
 * Storage type identifiers defined by the CDP `Storage` domain.
 *
 * Each constant carries the wire value Chrome expects both in
 * `Storage.getUsageAndQuota` `usageBreakdown` entries and as the comma-separated
 * `storageTypes` list of `Storage.clearDataForOrigin`.
 *
 * Unrecognized wire values resolve to [OTHER]; no type is ever fabricated.
 */
enum class StorageType(val wireValue: String) {
    APPCACHE("appcache"),
    COOKIES("cookies"),
    FILE_SYSTEMS("file_systems"),
    INDEXEDDB("indexeddb"),
    LOCAL_STORAGE("local_storage"),
    SHADER_CACHE("shader_cache"),
    WEBSQL("websql"),
    SERVICE_WORKERS("service_workers"),
    CACHE_STORAGE("cache_storage"),
    OTHER("other"),
    ALL("all");

    companion object {
        /** Maps a CDP wire type string to its [StorageType], defaulting to [OTHER]. */
        fun fromWire(value: String?): StorageType =
            entries.firstOrNull { it.wireValue == value } ?: OTHER
    }
}

/**
 * Availability of the `Storage` CDP domain for the bound origin/target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly.
 */
enum class StorageCapabilityStatus {
    /** `Storage.getUsageAndQuota` succeeded; the domain is functional. */
    SUPPORTED,

    /** The `Storage` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `Storage` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** A `Storage` command failed with an error. */
    FAILED,
}

/**
 * Outcome of a `Storage` operation.
 *
 * Mirrors the CDP error mapping contract: an operation either succeeds or
 * resolves to an explicit [StorageActionStatus] (`UNSUPPORTED`, `UNAVAILABLE`,
 * `FAILED`) with an optional diagnostic message and throwable. No mock or
 * hardcoded storage numbers are ever returned.
 *
 * @param T The value carried on success (e.g. [StorageUsageModel] or `Unit`).
 */
data class StorageActionResult<out T>(
    val status: StorageActionStatus,
    val value: T? = null,
    val message: String? = null,
    val throwable: Throwable? = null,
) {
    val isSuccess: Boolean get() = status == StorageActionStatus.SUCCESS

    companion object {
        fun <T> success(value: T): StorageActionResult<T> =
            StorageActionResult(StorageActionStatus.SUCCESS, value = value)

        fun <T> unsupported(message: String?, throwable: Throwable? = null): StorageActionResult<T> =
            StorageActionResult(StorageActionStatus.UNSUPPORTED, message = message, throwable = throwable)

        fun <T> unavailable(message: String?, throwable: Throwable? = null): StorageActionResult<T> =
            StorageActionResult(StorageActionStatus.UNAVAILABLE, message = message, throwable = throwable)

        fun <T> failed(message: String?, throwable: Throwable? = null): StorageActionResult<T> =
            StorageActionResult(StorageActionStatus.FAILED, message = message, throwable = throwable)
    }
}

/** Result status for a [StorageActionResult]. */
enum class StorageActionStatus {
    SUCCESS,
    UNSUPPORTED,
    UNAVAILABLE,
    FAILED,
}

/**
 * Storage usage (bytes) for a single [StorageType], as reported by Chrome's
 * `Storage.getUsageAndQuota` `usageBreakdown`.
 */
data class UsageForTypeModel(
    val storageType: StorageType,
    val usageBytes: Long,
)

/**
 * Origin-level storage usage and quota, parsed from a genuine
 * `Storage.getUsageAndQuota` response.
 *
 * All numeric fields originate from Chrome; [usageBreakdown] holds the per-type
 * usage breakdown as reported by the browser. [retrievedAt] is the local
 * retrieval timestamp (ms epoch).
 */
data class StorageUsageModel(
    val origin: String,
    val usageBytes: Long,
    val quotaBytes: Long,
    val usageBreakdown: List<UsageForTypeModel> = emptyList(),
    val retrievedAt: Long,
)