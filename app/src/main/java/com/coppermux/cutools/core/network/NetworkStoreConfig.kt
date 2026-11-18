package com.coppermux.cutools.core.network

/**
 * Configurable memory caps for the in-memory [NetworkRequestStore].
 *
 * High-frequency traffic can otherwise accumulate unbounded [NetworkRequestModel]s
 * (including their request/response headers and body buffers), risking an
 * out-of-memory condition. Phase 5E introduces strict, configurable limits:
 *
 *  - [maxRequests] caps how many request records are retained.
 *  - [maxBufferBytes] caps the approximate total retained bytes (request/response
 *    headers, URLs, post bodies, and per-entry overhead).
 *
 * When either cap is exceeded the oldest entries are evicted (oldest-first
 * circular buffer), so memory stays bounded under sustained high-frequency
 * traffic. A single entry larger than [maxBufferBytes] is still retained (its
 * newest and only record cannot be discarded without losing the request), but
 * all smaller entries are kept within the cap.
 */
data class NetworkStoreConfig(
    val maxRequests: Long = DEFAULT_MAX_REQUESTS,
    val maxBufferBytes: Long = DEFAULT_MAX_BUFFER_BYTES,
) {
    companion object {
        /** Default maximum number of tracked request chains. */
        const val DEFAULT_MAX_REQUESTS = 500L

        /** Default approximate byte cap (50 MiB) for retained request data. */
        const val DEFAULT_MAX_BUFFER_BYTES = 50L * 1024 * 1024
    }
}