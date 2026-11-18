package com.coppermux.cutools.ui.security

import com.coppermux.cutools.core.security.models.CertificateDetailsModel
import com.coppermux.cutools.core.security.models.CertificateErrorModel
import com.coppermux.cutools.core.security.models.CertificateModel
import com.coppermux.cutools.core.security.models.SecurityCapabilityStatus
import com.coppermux.cutools.core.security.models.SecurityExplanationModel
import com.coppermux.cutools.core.security.models.SecurityStateModel

/**
 * Immutable UI snapshot for the Security inspection panel (Phase 13).
 *
 * Mirrors genuine engine truth only — [status] is derived from the
 * [com.coppermux.cutools.core.security.SecurityEngine] capability,
 * [securityState] holds the last genuine `Security.securityStateChanged`
 * outcome, [explanations] / [certificateDetails] the last genuine security
 * explanation / certificate chain, [certificate] the last genuine
 * `Security.getCertificate` result, and [errorMessage] carries an explicit
 * renderable error resolved from an engine failure. No mock or synthetic TLS /
 * certificate state is ever produced (No-Fake-Capability rule).
 *
 * @property status The explicit `Security` capability (SUPPORTED / UNSUPPORTED
 *   / UNAVAILABLE / FAILED).
 * @property enabled Whether `Security` domain notifications are active.
 * @property securityState The latest genuine security state, or null when none
 *   observed.
 * @property schemeIsCryptographic Whether the current scheme is cryptographic.
 * @property explanations The genuine security explanation badges.
 * @property certificateDetails The active certificate chain from the security
 *   event, or null.
 * @property certificateErrors The genuine `Security.certificateError` reports.
 * @property certificate The last genuine `Security.getCertificate` result, or
 *   null.
 * @property origin The origin filter / certificate lookup target.
 * @property isLoading True while a fetch/refresh command is in flight.
 * @property errorMessage A renderable banner string, or null when no error.
 */
data class SecurityUiState(
    val status: SecurityCapabilityStatus = SecurityCapabilityStatus.UNAVAILABLE,
    val enabled: Boolean = false,
    val securityState: SecurityStateModel? = null,
    val schemeIsCryptographic: Boolean = false,
    val explanations: List<SecurityExplanationModel> = emptyList(),
    val certificateDetails: CertificateDetailsModel? = null,
    val certificateErrors: List<CertificateErrorModel> = emptyList(),
    val certificate: CertificateModel? = null,
    val origin: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)