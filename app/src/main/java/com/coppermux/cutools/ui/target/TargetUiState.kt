package com.coppermux.cutools.ui.target

import com.coppermux.cutools.core.target.models.TargetCapabilityStatus
import com.coppermux.cutools.core.target.models.TargetInfoModel

/**
 * Immutable UI snapshot for the Target discovery panel (Phase 13).
 *
 * Mirrors genuine engine truth only — [status] is derived from the
 * [com.coppermux.cutools.core.target.TargetEngine] capability, [targets] holds
 * the last genuine `Target.getTargets` outcome, [attachedTargetId] reflects the
 * genuine attach/detach outcome, [discoveryEnabled] reflects the last genuine
 * `Target.setDiscoverTargets` result, and [errorMessage] carries an explicit
 * renderable error resolved from an engine failure. No mock or synthetic target
 * state is ever produced (No-Fake-Capability rule).
 *
 * @property status The explicit `Target` capability (SUPPORTED / UNSUPPORTED /
 *   UNAVAILABLE / FAILED).
 * @property targets The live genuine target discovery stream snapshot.
 * @property attachedTargetId The currently attached target id, or null when
 *   none is attached.
 * @property discoveryEnabled Whether `Target.setDiscoverTargets(true)` is
 *   currently active.
 * @property isLoading True while a refresh/attach/detach command is in flight.
 * @property searchQuery The active title/type/url filter.
 * @property errorMessage A renderable banner string, or null when no error.
 */
data class TargetUiState(
    val status: TargetCapabilityStatus = TargetCapabilityStatus.UNAVAILABLE,
    val targets: List<TargetInfoModel> = emptyList(),
    val attachedTargetId: String? = null,
    val discoveryEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val errorMessage: String? = null,
)