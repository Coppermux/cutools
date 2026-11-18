package com.coppermux.cutools.tools.application.engine

import com.coppermux.cutools.core.model.Event
import com.coppermux.cutools.core.target.SessionInfo
import com.coppermux.cutools.tools.application.models.ServiceWorkerErrorLogModel
import com.coppermux.cutools.tools.application.models.ServiceWorkerRegistrationModel
import com.coppermux.cutools.tools.application.models.ServiceWorkerRunningStatus
import com.coppermux.cutools.tools.application.models.ServiceWorkerVersionModel
import com.coppermux.cutools.tools.application.models.ServiceWorkerVersionStatus

/**
 * Applies genuine Chrome `ServiceWorker` domain events to a [ServiceWorkerStore]
 * without fabricating any state.
 *
 * Handled events:
 *  - `ServiceWorker.workerRegistrationUpdated` → [ServiceWorkerStore.onRegistrationsUpdated]
 *  - `ServiceWorker.workerVersionUpdated`      → [ServiceWorkerStore.onVersionsUpdated]
 *  - `ServiceWorker.workerErrorReported`       → [ServiceWorkerStore.onErrorReported]
 *
 * Only Chrome-supplied fields are populated; malformed payloads (non-map params,
 * missing identifiers) are tolerated and return without mutating the store, so
 * the event pipeline never crashes.
 */
class CdpServiceWorkerEventListener(private val store: ServiceWorkerStore) {

    /**
     * Applies a classified ServiceWorker event. Events for other sessions must be
     * filtered by the caller before reaching here.
     */
    fun apply(event: Event, session: SessionInfo) {
        if (event.params !is Map<*, *>) return
        when (event.method) {
            "ServiceWorker.workerRegistrationUpdated" -> applyRegistrations(event.params)
            "ServiceWorker.workerVersionUpdated" -> applyVersions(event.params)
            "ServiceWorker.workerErrorReported" -> applyError(event.params)
        }
    }

    private fun applyRegistrations(params: Map<*, *>) {
        val rawList = params["registrations"] as? List<*> ?: return
        val registrations = rawList.mapNotNull { parseRegistration(it) }
        if (registrations.isNotEmpty()) store.onRegistrationsUpdated(registrations)
    }

    private fun applyVersions(params: Map<*, *>) {
        val rawList = params["versions"] as? List<*> ?: return
        val versions = rawList.mapNotNull { parseVersion(it) }
        if (versions.isNotEmpty()) store.onVersionsUpdated(versions)
    }

    private fun applyError(params: Map<*, *>) {
        val errorType = (params["errorType"] as? String) ?: ""
        val errorMessage = (params["errorMessage"] as? String) ?: ""
        if (errorType.isEmpty() && errorMessage.isEmpty()) return
        store.onErrorReported(
            ServiceWorkerErrorLogModel(
                versionId = params["versionId"] as? String,
                registrationId = params["registrationId"] as? String,
                errorType = errorType,
                errorMessage = errorMessage,
                reportedAt = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        /** Parses a CDP `ServiceWorkerRegistration` entry, or null when malformed. */
        fun parseRegistration(raw: Any?): ServiceWorkerRegistrationModel? {
            if (raw !is Map<*, *>) return null
            val registrationId = raw["registrationId"] as? String ?: return null
            return ServiceWorkerRegistrationModel(
                registrationId = registrationId,
                scopeURL = (raw["scopeURL"] as? String) ?: "",
                isDeleted = (raw["isDeleted"] as? Boolean) ?: false,
            )
        }

        /** Parses a CDP `ServiceWorkerVersion` entry, or null when malformed. */
        fun parseVersion(raw: Any?): ServiceWorkerVersionModel? {
            if (raw !is Map<*, *>) return null
            val versionId = raw["versionId"] as? String ?: return null
            return ServiceWorkerVersionModel(
                versionId = versionId,
                registrationId = (raw["registrationId"] as? String) ?: "",
                scriptURL = (raw["scriptURL"] as? String) ?: "",
                runningStatus = ServiceWorkerRunningStatus.fromWire(raw["runningStatus"] as? String),
                status = ServiceWorkerVersionStatus.fromWire(raw["status"] as? String),
                scriptLastModified = raw["scriptLastModifiedTime"] as? String ?: raw["scriptLastModified"] as? String,
            )
        }
    }
}

/**
 * Receives parsed `ServiceWorker` events. Implementations must apply updates
 * atomically so concurrent event streams never lose an update.
 */
interface ServiceWorkerStore {
    fun onRegistrationsUpdated(registrations: List<ServiceWorkerRegistrationModel>)
    fun onVersionsUpdated(versions: List<ServiceWorkerVersionModel>)
    fun onErrorReported(error: ServiceWorkerErrorLogModel)
}