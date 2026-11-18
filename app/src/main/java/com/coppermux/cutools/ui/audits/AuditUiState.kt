package com.coppermux.cutools.ui.audits

import com.coppermux.cutools.core.audits.models.AuditCapabilityStatus
import com.coppermux.cutools.core.audits.models.AuditIssueModel
import com.coppermux.cutools.core.audits.models.ContrastCheckResultModel

/**
 * Immutable UI snapshot for the Audits panel (Phase 11B).
 *
 * Mirrors genuine engine truth only — [status] is derived from the
 * [com.coppermux.cutools.core.audits.AuditEngine] capability, [enabled] reflects
 * whether `Audits` notifications are active, [issues] holds the real-time
 * `Audits.issueAdded` stream snapshot, [contrastResult] the last genuine
 * `Audits.checkContrast` outcome, and [errorMessage] carries an explicit
 * renderable error resolved from an engine failure. No mock or synthetic issue
 * state is ever produced (No-Fake-Capability rule).
 *
 * @property status The explicit `Audits` capability (SUPPORTED / UNSUPPORTED /
 *   UNAVAILABLE / FAILED).
 * @property enabled Whether `Audits` domain notifications are currently active.
 * @property isLoading True while a contrast check command is in flight.
 * @property issues The genuine real-time issue stream, in observation order.
 * @property searchQuery The live filter query applied to the issue stream.
 * @property contrastResult The last genuine contrast check result, or null.
 * @property errorMessage A renderable banner string, or null when no error.
 */
data class AuditUiState(
    val status: AuditCapabilityStatus = AuditCapabilityStatus.UNAVAILABLE,
    val enabled: Boolean = false,
    val isLoading: Boolean = false,
    val issues: List<AuditIssueModel> = emptyList(),
    val searchQuery: String = "",
    val contrastResult: ContrastCheckResultModel? = null,
    val errorMessage: String? = null,
)