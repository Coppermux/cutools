package com.coppermux.cutools.tools.application.models

/**
 * A single browser cache storage bucket, parsed strictly from a genuine CDP
 * `CacheStorage.requestCacheNames` `caches` entry.
 *
 * [cacheId] is the opaque bucket identifier used by subsequent commands,
 * [securityOrigin] the origin the bucket belongs to, and [cacheName] the
 * bucket name reported by Chrome. No bucket is ever fabricated.
 */
data class CacheModel(
    val cacheId: String,
    val securityOrigin: String,
    val cacheName: String,
)

/**
 * A single cached HTTP entry, parsed strictly from a genuine CDP
 * `CacheStorage.requestEntries` entry.
 *
 * [requestUrl] is the cached request URL, [requestMethod] the cached request
 * HTTP method (e.g. `GET`), and [responseHeaderNames] the names of the cached
 * response headers as reported by Chrome.
 */
data class CacheEntryModel(
    val requestUrl: String = "",
    val responseHeaderNames: List<String> = emptyList(),
    val requestMethod: String = "",
)

/**
 * Availability of the `CacheStorage` CDP domain for the bound target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class CacheStorageCapabilityStatus {
    /** `CacheStorage` commands are functional on the attached target. */
    SUPPORTED,

    /** A `CacheStorage` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `CacheStorage` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** A `CacheStorage` command failed with an error. */
    FAILED,
}

/** Result status for a [CacheActionResult]. */
enum class CacheActionStatus {
    SUCCESS,
    UNSUPPORTED,
    UNAVAILABLE,
    FAILED,
}

/**
 * Outcome of a `CacheStorage` operation.
 *
 * Mirrors the CDP error mapping contract: an operation either succeeds or
 * resolves to an explicit [CacheActionStatus] (`UNSUPPORTED`, `UNAVAILABLE`,
 * `FAILED`) with an optional diagnostic message and throwable. No mock or
 * hardcoded cache/entry values are ever returned.
 *
 * @param T The value carried on success (e.g. `List<CacheEntryModel>` or `Unit`).
 */
data class CacheActionResult<out T>(
    val status: CacheActionStatus,
    val value: T? = null,
    val message: String? = null,
    val throwable: Throwable? = null,
) {
    val isSuccess: Boolean get() = status == CacheActionStatus.SUCCESS

    companion object {
        fun <T> success(value: T): CacheActionResult<T> =
            CacheActionResult(CacheActionStatus.SUCCESS, value = value)

        fun <T> unsupported(message: String?, throwable: Throwable? = null): CacheActionResult<T> =
            CacheActionResult(CacheActionStatus.UNSUPPORTED, message = message, throwable = throwable)

        fun <T> unavailable(message: String?, throwable: Throwable? = null): CacheActionResult<T> =
            CacheActionResult(CacheActionStatus.UNAVAILABLE, message = message, throwable = throwable)

        fun <T> failed(message: String?, throwable: Throwable? = null): CacheActionResult<T> =
            CacheActionResult(CacheActionStatus.FAILED, message = message, throwable = throwable)
    }
}