package com.coppermux.cutools.ui.network

import com.coppermux.cutools.core.network.FetchInterceptionState
import com.coppermux.cutools.core.network.FetchLifecyclePhase
import com.coppermux.cutools.core.network.NetworkCapabilityStatus
import com.coppermux.cutools.core.network.NetworkLifecyclePhase
import com.coppermux.cutools.core.network.NetworkRequestModel
import com.coppermux.cutools.core.network.NetworkState

/**
 * Immutable, renderable state of the request-list view, binding the structured
 * [NetworkState] and [FetchInterceptionState] from the underlying engines to
 * the active presentation [filter] and [selectedRequestId].
 *
 * The visible request set is the deterministic intersection of the browser-owned
 * [NetworkState.requests] and the pure [NetworkRequestFilter] — the UI renders
 * this model and never owns browser truth.
 */
data class NetworkMonitorViewModel(
    val network: NetworkState = NetworkState.empty(),
    val interception: FetchInterceptionState = FetchInterceptionState(),
    val filter: NetworkRequestFilter = NetworkRequestFilter(),
    val selectedRequestId: String? = null,
) {
    val isReady: Boolean get() = network.isReady

    val isInactive: Boolean get() = network.isInactive

    val totalCount: Int get() = network.requests.size

    /** Requests shown after applying the active filter + search. */
    val visibleRequests: List<NetworkRequestModel>
        get() = filter.apply(network.requests)

    val visibleCount: Int get() = visibleRequests.size

    val pendingCount: Int get() = network.requests.count { it.isPending }

    val failedCount: Int get() = network.requests.count { it.isFailed }

    /** The selected request from the underlying (unfiltered) set. */
    val selectedRequest: NetworkRequestModel?
        get() = selectedRequestId?.let { id -> network.requests.find { it.requestId == id } }

    /** A single-line status summary for the monitor header. */
    fun statusText(): String = buildString {
        when (network.phase) {
            NetworkLifecyclePhase.INACTIVE -> append("Not attached to a target")
            NetworkLifecyclePhase.ENABLING -> append("Enabling Network…")
            NetworkLifecyclePhase.READY -> {
                append("Ready · ")
                append(visibleCount)
                append('/').append(totalCount).append(" shown")
                if (failedCount > 0) append(" · ").append(failedCount).append(" failed")
            }
            NetworkLifecyclePhase.UNSUPPORTED -> append("Network unsupported for this target")
            NetworkLifecyclePhase.FAILED -> append("Network failed: ").append(network.error?.message ?: "error")
        }
        val cap = when (network.capability) {
            NetworkCapabilityStatus.SUPPORTED -> ""
            NetworkCapabilityStatus.UNKNOWN -> " · capability: probing…"
            NetworkCapabilityStatus.UNSUPPORTED -> " · capability: unsupported"
            NetworkCapabilityStatus.FAILED -> " · capability: failed"
        }
        append(cap)
    }

    /** A short interception-capability label for the rules header. */
    fun interceptionText(): String = when {
        interception.isReady -> "Interception ready (${interception.interceptedCount} intercepted)"
        interception.isEnabling -> "Enabling interception…"
        interception.phase == FetchLifecyclePhase.DISABLED -> "Interception disabled"
        else -> "Interception not ready"
    }

    /** A copy with the presentation filter updated. */
    fun withFilter(filter: NetworkRequestFilter): NetworkMonitorViewModel = copy(filter = filter)

    /** A copy with the selection updated. */
    fun select(requestId: String?): NetworkMonitorViewModel = copy(selectedRequestId = requestId)

    companion object {
        fun empty(): NetworkMonitorViewModel = NetworkMonitorViewModel()
    }
}