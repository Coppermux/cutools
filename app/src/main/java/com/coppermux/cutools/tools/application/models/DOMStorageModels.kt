package com.coppermux.cutools.tools.application.models

/**
 * Kind of browser key-value storage exposed through the CDP `DOMStorage`
 * domain. [LOCAL_STORAGE] maps to the `isLocalStorage=true` flag on a CDP
 * `StorageId`, [SESSION_STORAGE] to `isLocalStorage=false`.
 */
enum class DOMStorageType(val isLocalStorage: Boolean) {
    LOCAL_STORAGE(true),
    SESSION_STORAGE(false);

    companion object {
        /** Maps the CDP `isLocalStorage` wire flag to a [DOMStorageType]. */
        fun fromWire(isLocalStorage: Boolean?): DOMStorageType =
            if (isLocalStorage == true) LOCAL_STORAGE else SESSION_STORAGE
    }
}

/**
 * Identity of a browser storage area used by every `DOMStorage` command and
 * carried inside each `DOMStorage` event's `storageId`.
 *
 * [securityOrigin] and [isLocalStorage] are the mandatory CDP `StorageId`
 * fields; [storageKey] is the optional newer Chrome field, preserved when
 * present.
 */
data class StorageIdModel(
    val securityOrigin: String,
    val isLocalStorage: Boolean,
    val storageKey: String? = null,
) {
    /** The storage kind this ID denotes. */
    val storageType: DOMStorageType get() = DOMStorageType.fromWire(isLocalStorage)

    /**
     * Whether two storage IDs denote the same storage area. Identity is
     * determined by the primary CDP fields ([securityOrigin] + [isLocalStorage]);
     * the optional [storageKey] is not required to match.
     */
    fun matches(other: StorageIdModel?): Boolean =
        other != null && securityOrigin == other.securityOrigin && isLocalStorage == other.isLocalStorage
}

/** A single key-value pair inside a [StorageIdModel] storage area. */
data class DOMStorageItemModel(
    val key: String,
    val value: String,
)

/**
 * Availability of the `DOMStorage` CDP domain for the bound target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class DOMStorageCapabilityStatus {
    /** `DOMStorage` commands/notifications are functional. */
    SUPPORTED,

    /** The `DOMStorage` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `DOMStorage` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** A `DOMStorage` command failed with an error. */
    FAILED,
}

/** Outcome status for a [DOMStorageActionResult]. */
enum class DOMStorageActionStatus {
    SUCCESS,
    UNSUPPORTED,
    UNAVAILABLE,
    FAILED,
}

/**
 * Outcome of a `DOMStorage` operation.
 *
 * Mirrors the CDP error mapping contract: an operation either succeeds or
 * resolves to an explicit [DOMStorageActionStatus] (`UNSUPPORTED`, `UNAVAILABLE`,
 * `FAILED`) with an optional diagnostic message and throwable. No mock or
 * hardcoded storage values are ever returned.
 *
 * @param T The value carried on success (e.g. `List<DOMStorageItemModel>` or `Unit`).
 */
data class DOMStorageActionResult<out T>(
    val status: DOMStorageActionStatus,
    val value: T? = null,
    val message: String? = null,
    val throwable: Throwable? = null,
) {
    val isSuccess: Boolean get() = status == DOMStorageActionStatus.SUCCESS

    companion object {
        fun <T> success(value: T): DOMStorageActionResult<T> =
            DOMStorageActionResult(DOMStorageActionStatus.SUCCESS, value = value)

        fun <T> unsupported(message: String?, throwable: Throwable? = null): DOMStorageActionResult<T> =
            DOMStorageActionResult(DOMStorageActionStatus.UNSUPPORTED, message = message, throwable = throwable)

        fun <T> unavailable(message: String?, throwable: Throwable? = null): DOMStorageActionResult<T> =
            DOMStorageActionResult(DOMStorageActionStatus.UNAVAILABLE, message = message, throwable = throwable)

        fun <T> failed(message: String?, throwable: Throwable? = null): DOMStorageActionResult<T> =
            DOMStorageActionResult(DOMStorageActionStatus.FAILED, message = message, throwable = throwable)
    }
}

/**
 * Immutable snapshot of the currently inspected `DOMStorage` state.
 *
 * [origin] / [storageId] / [storageType] describe the active inspected storage
 * area (set by a successful `getItems`), [items] holds the genuine key-value
 * map kept current by live CDP events and command outcomes, and [capability]
 * / [enabled] describe the `DOMStorage` domain state.
 */
data class DOMStorageState(
    val sessionId: String? = null,
    val targetId: String? = null,
    val origin: String? = null,
    val storageId: StorageIdModel? = null,
    val storageType: DOMStorageType? = null,
    val enabled: Boolean = false,
    val capability: DOMStorageCapabilityStatus? = null,
    val items: Map<String, String> = emptyMap(),
) {
    val itemCount: Int get() = items.size

    companion object {
        fun empty(): DOMStorageState = DOMStorageState()
    }
}
