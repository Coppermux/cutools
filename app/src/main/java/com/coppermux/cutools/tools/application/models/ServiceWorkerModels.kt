package com.coppermux.cutools.tools.application.models

/**
 * Lifecycle running status of a service worker version, mirroring the CDP
 * `ServiceWorkerVersion` `runningStatus` wire value.
 *
 * Values are mapped strictly from Chrome; an unrecognized or absent wire value
 * resolves to `null` on [ServiceWorkerVersionModel.runningStatus] — no running
 * state is ever assumed (Exact Mapping rule).
 */
enum class ServiceWorkerRunningStatus(val wireValue: String) {
    STARTING("starting"),
    RUNNING("running"),
    STOPPING("stopping"),
    STOPPED("stopped");

    companion object {
        /** Maps a CDP `runningStatus` wire string to a [ServiceWorkerRunningStatus], or null. */
        fun fromWire(value: String?): ServiceWorkerRunningStatus? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Installation lifecycle status of a service worker version, mirroring the CDP
 * `ServiceWorkerVersion` `status` wire value.
 *
 * Values are mapped strictly from Chrome; an unrecognized or absent wire value
 * resolves to `null` on [ServiceWorkerVersionModel.status] — no status is ever
 * fabricated (Exact Mapping rule).
 */
enum class ServiceWorkerVersionStatus(val wireValue: String) {
    NEW("new"),
    INSTALLING("installing"),
    INSTALLED("installed"),
    ACTIVATING("activating"),
    ACTIVATED("activated"),
    RUNNING("running"),
    REDUNDANT("redundant"),
    DEACTIVATING("deactivating"),
    STOPPED("stopped"),
    UNINSTALLING("uninstalling");

    companion object {
        /** Maps a CDP `status` wire string to a [ServiceWorkerVersionStatus], or null. */
        fun fromWire(value: String?): ServiceWorkerVersionStatus? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * A single service worker registration, parsed strictly from a genuine CDP
 * `ServiceWorker.workerRegistrationUpdated` `registrations` entry.
 *
 * [registrationId] is the opaque registration identifier,
 * [scopeURL] the registration scope URL reported by Chrome, and [isDeleted]
 * the genuine Chrome deletion flag. No registration is ever fabricated.
 */
data class ServiceWorkerRegistrationModel(
    val registrationId: String,
    val scopeURL: String = "",
    val isDeleted: Boolean = false,
)

/**
 * A single service worker version, parsed strictly from a genuine CDP
 * `ServiceWorker.workerVersionUpdated` `versions` entry.
 *
 * [versionId] / [registrationId] identify the worker instance and its parent
 * registration, [scriptURL] is the worker script location, [runningStatus] /
 * [status] the genuine lifecycle states reported by Chrome, and
 * [scriptLastModified] the optional last-modified timestamp. No version is ever
 * fabricated.
 */
data class ServiceWorkerVersionModel(
    val versionId: String,
    val registrationId: String = "",
    val scriptURL: String = "",
    val runningStatus: ServiceWorkerRunningStatus? = null,
    val status: ServiceWorkerVersionStatus? = null,
    val scriptLastModified: String? = null,
)

/**
 * A single service worker error report, parsed strictly from a genuine CDP
 * `ServiceWorker.workerErrorReported` event. [reportedAt] is the local
 * observation timestamp (ms epoch).
 */
data class ServiceWorkerErrorLogModel(
    val versionId: String? = null,
    val registrationId: String? = null,
    val errorType: String = "",
    val errorMessage: String = "",
    val reportedAt: Long = 0L,
)

/**
 * Availability of the `ServiceWorker` CDP domain for the bound target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class ServiceWorkerCapabilityStatus {
    /** `ServiceWorker` commands/notifications are functional on the attached target. */
    SUPPORTED,

    /** A `ServiceWorker` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `ServiceWorker` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** A `ServiceWorker` command failed with an error. */
    FAILED,
}

/** Result status for a [ServiceWorkerActionResult]. */
enum class ServiceWorkerActionStatus {
    SUCCESS,
    UNSUPPORTED,
    UNAVAILABLE,
    FAILED,
}

/**
 * Outcome of a `ServiceWorker` operation.
 *
 * Mirrors the CDP error mapping contract: an operation either succeeds or
 * resolves to an explicit [ServiceWorkerActionStatus] (`UNSUPPORTED`,
 * `UNAVAILABLE`, `FAILED`) with an optional diagnostic message and throwable.
 * No mock or hardcoded service worker values are ever returned.
 *
 * @param T The value carried on success (e.g. `Unit`).
 */
data class ServiceWorkerActionResult<out T>(
    val status: ServiceWorkerActionStatus,
    val value: T? = null,
    val message: String? = null,
    val throwable: Throwable? = null,
) {
    val isSuccess: Boolean get() = status == ServiceWorkerActionStatus.SUCCESS

    companion object {
        fun <T> success(value: T): ServiceWorkerActionResult<T> =
            ServiceWorkerActionResult(ServiceWorkerActionStatus.SUCCESS, value = value)

        fun <T> unsupported(message: String?, throwable: Throwable? = null): ServiceWorkerActionResult<T> =
            ServiceWorkerActionResult(ServiceWorkerActionStatus.UNSUPPORTED, message = message, throwable = throwable)

        fun <T> unavailable(message: String?, throwable: Throwable? = null): ServiceWorkerActionResult<T> =
            ServiceWorkerActionResult(ServiceWorkerActionStatus.UNAVAILABLE, message = message, throwable = throwable)

        fun <T> failed(message: String?, throwable: Throwable? = null): ServiceWorkerActionResult<T> =
            ServiceWorkerActionResult(ServiceWorkerActionStatus.FAILED, message = message, throwable = throwable)
    }
}

/**
 * Immutable snapshot of the currently inspected `ServiceWorker` state.
 *
 * [registrations] / [versions] are keyed by their opaque CDP identifiers and
 * kept current by genuine `ServiceWorker` events and command outcomes,
 * [errorLogs] holds the genuine worker error reports in observation order,
 * and [capability] / [enabled] describe the `ServiceWorker` domain state.
 */
data class ServiceWorkerState(
    val sessionId: String? = null,
    val targetId: String? = null,
    val enabled: Boolean = false,
    val capability: ServiceWorkerCapabilityStatus? = null,
    val registrations: Map<String, ServiceWorkerRegistrationModel> = emptyMap(),
    val versions: Map<String, ServiceWorkerVersionModel> = emptyMap(),
    val errorLogs: List<ServiceWorkerErrorLogModel> = emptyList(),
) {
    val registrationCount: Int get() = registrations.size
    val versionCount: Int get() = versions.size
    val errorCount: Int get() = errorLogs.size

    companion object {
        fun empty(): ServiceWorkerState = ServiceWorkerState()
    }
}