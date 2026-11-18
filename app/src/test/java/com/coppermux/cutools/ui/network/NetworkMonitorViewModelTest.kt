package com.coppermux.cutools.ui.network

import com.coppermux.cutools.core.network.FetchInterceptionState
import com.coppermux.cutools.core.network.FetchLifecyclePhase
import com.coppermux.cutools.core.network.NetworkCapabilityStatus
import com.coppermux.cutools.core.network.NetworkLifecyclePhase
import com.coppermux.cutools.core.network.NetworkRequestModel
import com.coppermux.cutools.core.network.NetworkRequestStage
import com.coppermux.cutools.core.network.NetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for Phase 5D monitor view-model: the state flow that
 * binds [NetworkState], [FetchInterceptionState] and the presentation
 * [NetworkRequestFilter] into a renderable request list.
 */
class NetworkMonitorViewModelTest {

    private fun model(
        id: String,
        stage: NetworkRequestStage = NetworkRequestStage.FINISHED,
        status: Int? = 200,
        resourceType: String? = "xhr",
    ) = NetworkRequestModel(
        requestId = id,
        stage = stage,
        url = "https://api.example.com/$id",
        method = "GET",
        resourceType = resourceType,
        responseStatus = status,
        sessionId = "s1",
    )

    private fun network(vararg requests: NetworkRequestModel): NetworkState =
        NetworkState(
            requests = requests.toList(),
            sessionId = "s1",
            phase = NetworkLifecyclePhase.READY,
            capability = NetworkCapabilityStatus.SUPPORTED,
        )

    // ============ filtering integration ============

    @Test
    fun visibleRequests_applyActiveFilter() {
        val vm = NetworkMonitorViewModel(
            network = network(
                model("a", resourceType = "script"),
                model("b", resourceType = "image"),
                model("c", resourceType = "script"),
            ),
            filter = NetworkRequestFilter(resourceType = NetworkResourceType.JS),
        )
        assertEquals(listOf("a", "c"), vm.visibleRequests.map { it.requestId })
        assertEquals(2, vm.visibleCount)
        assertEquals(3, vm.totalCount)
    }

    @Test
    fun visibleRequests_withSearch() {
        val vm = NetworkMonitorViewModel(
            network = network(
                model("a", resourceType = "script").copy(url = "https://x/main.js"),
                model("b", resourceType = "script").copy(url = "https://x/worker.js"),
            ),
            filter = NetworkRequestFilter(searchText = "worker"),
        )
        assertEquals(listOf("b"), vm.visibleRequests.map { it.requestId })
    }

    @Test
    fun defaultFilter_showsEverything() {
        val vm = NetworkMonitorViewModel(network = network(model("a"), model("b")))
        assertTrue(vm.filter.isAll)
        assertEquals(2, vm.visibleCount)
        assertEquals(2, vm.totalCount)
    }

    // ============ aggregation ============

    @Test
    fun counts_pendingAndFailed() {
        val vm = NetworkMonitorViewModel(network = network(
            model("a", stage = NetworkRequestStage.PENDING),
            model("b", stage = NetworkRequestStage.FAILED),
            model("c", stage = NetworkRequestStage.FINISHED),
        ))
        assertEquals(1, vm.pendingCount)
        assertEquals(1, vm.failedCount)
        assertTrue(vm.isReady)
    }

    @Test
    fun inactiveState_reportsNotReady() {
        val vm = NetworkMonitorViewModel()
        assertTrue(vm.isInactive)
        assertFalse(vm.isReady)
    }

    // ============ selection ============

    @Test
    fun selectedRequest_resolvesFromRequestSet() {
        val vm = NetworkMonitorViewModel(
            network = network(model("a"), model("b")),
            selectedRequestId = "b",
        )
        assertEquals("b", vm.selectedRequest?.requestId)
    }

    @Test
    fun select_copySetsSelection() {
        val vm = NetworkMonitorViewModel(network = network(model("a"))).select("a")
        assertEquals("a", vm.selectedRequestId)
        assertNull(NetworkMonitorViewModel(network = network(model("a"))).selectedRequestId)
    }

    @Test
    fun selectUnknownRequest_yieldsNullSelected() {
        val vm = NetworkMonitorViewModel(network = network(model("a")), selectedRequestId = "zzz")
        assertNull(vm.selectedRequest)
    }

    // ============ status / labels ============

    @Test
    fun statusText_inactive() {
        assertEquals(
            "Not attached to a target · capability: probing…",
            NetworkMonitorViewModel().statusText(),
        )
    }

    @Test
    fun statusText_readyWithCounts() {
        val vm = NetworkMonitorViewModel(network = network(model("a"), model("b")))
        val text = vm.statusText()
        assertTrue(text.contains("Ready"))
        assertTrue(text.contains("2/2 shown"))
    }

    @Test
    fun statusText_includesFailedCount() {
        val vm = NetworkMonitorViewModel(network = network(
            model("a", stage = NetworkRequestStage.FAILED),
            model("b"),
        ))
        val text = vm.statusText()
        assertTrue(text.contains("1 failed"))
    }

    @Test
    fun statusText_capabilityProbing() {
        val vm = NetworkMonitorViewModel(
            network = NetworkState(phase = NetworkLifecyclePhase.ENABLING, capability = NetworkCapabilityStatus.UNKNOWN),
        )
        val text = vm.statusText()
        assertTrue(text.contains("Enabling"))
    }

    @Test
    fun statusText_unsupported() {
        val vm = NetworkMonitorViewModel(
            network = NetworkState(phase = NetworkLifecyclePhase.UNSUPPORTED, capability = NetworkCapabilityStatus.UNSUPPORTED),
        )
        assertTrue(vm.statusText().contains("unsupported"))
    }

    @Test
    fun interceptionText_reflectsLifecycle() {
        assertEquals(
            "Interception not ready",
            NetworkMonitorViewModel(interception = FetchInterceptionState()).interceptionText(),
        )
        val ready = NetworkMonitorViewModel(
            interception = FetchInterceptionState(
                phase = FetchLifecyclePhase.READY,
                interceptedCount = 5,
            ),
        )
        assertTrue(ready.interceptionText().contains("5 intercepted"))
        val disabled = NetworkMonitorViewModel(
            interception = FetchInterceptionState(phase = FetchLifecyclePhase.DISABLED),
        )
        assertTrue(disabled.interceptionText().contains("disabled"))
    }

    @Test
    fun withFilter_preservesSelectionAndEngines() {
        val vm = NetworkMonitorViewModel(
            network = network(model("a")),
            selectedRequestId = "a",
        ).withFilter(NetworkRequestFilter(resourceType = NetworkResourceType.JS))
        assertEquals(NetworkResourceType.JS, vm.filter.resourceType)
        assertEquals("a", vm.selectedRequestId)
    }

    @Test
    fun emptyViewModel_isInactiveAndEmpty() {
        val vm = NetworkMonitorViewModel.empty()
        assertEquals(0, vm.totalCount)
        assertTrue(vm.isInactive)
    }
}