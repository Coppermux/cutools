package com.coppermux.cutools.core.security.models

/**
 * Active TLS security state of the currently inspected connection, mirroring the
 * CDP `SecurityState` enum reported by `Security.securityStateChanged`.
 *
 * Values are mapped strictly from Chrome; an unrecognized or absent wire value
 * resolves to `null` on [SecurityExplanationModel.securityState] — no security
 * state is ever assumed (Exact Mapping rule).
 */
enum class SecurityStateModel(val wireValue: String) {
    UNKNOWN("unknown"),
    NEUTRAL("neutral"),
    INSECURE("insecure"),
    SECURE("secure"),
    INFO("info");

    companion object {
        /** Maps a CDP `SecurityState` wire string to a [SecurityStateModel], or null. */
        fun fromWire(value: String?): SecurityStateModel? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Mixed-content classification of a connection security explanation, mirroring
 * the CDP `MixedContentType` wire value on `SecurityStateExplanation`.
 *
 * Unrecognized or absent wire values resolve to `null` — no mixed-content type
 * is ever fabricated.
 */
enum class MixedContentType(val wireValue: String) {
    BLOCKABLE("blockable"),
    OPTIONALLY_BLOCKABLE("optionally-blockable"),
    NONE("none");

    companion object {
        /** Maps a CDP `MixedContentType` wire string to a [MixedContentType], or null. */
        fun fromWire(value: String?): MixedContentType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Immutable wrapper for a CDP `SecurityStateExplanation` entry, parsed strictly
 * from a genuine `Security.securityStateChanged` `explanations` element.
 *
 * [securityState] / [title] / [summary] / [description] describe the explanation
 * the browser supplied, [hasCertificate] / [mixedContentType] the genuine
 * Chrome flags, [certificate] the certificate chain (as raw PEM strings) Chrome
 * reported, and [recommendations] any optional remediation advice. No
 * explanation is ever fabricated (No-Fake-Capability rule).
 */
data class SecurityExplanationModel(
    val securityState: SecurityStateModel? = null,
    val title: String = "",
    val summary: String = "",
    val description: String = "",
    val hasCertificate: Boolean = false,
    val mixedContentType: MixedContentType? = null,
    val certificate: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
)

/**
 * Immutable wrapper capturing the parsed parameters of the active TLS certificate
 * chain, mirroring the CDP `CertificateSecurityState` wire fields.
 *
 * [subjectName] / [issuer] identify the certificate subject and issuer,
 * [validFrom] / [validTo] the CDP `TimeSinceEpoch` validity bounds (seconds),
 * [protocol] / [cipher] / [keyExchange] (and optional [keyExchangeGroup]) the
 * negotiated TLS parameters, and [certificate] the reported X509 chain. All
 * fields originate from Chrome — none are fabricated.
 */
data class CertificateDetailsModel(
    val subjectName: String = "",
    val issuer: String = "",
    val validFrom: Double = 0.0,
    val validTo: Double = 0.0,
    val protocol: String = "",
    val cipher: String = "",
    val keyExchange: String = "",
    val keyExchangeGroup: String? = null,
    val certificate: List<String> = emptyList(),
)

/**
 * A genuine CDP `Security.certificateError` report observed for the bound
 * session. [eventId] is the opaque error identifier, [errorType] the failure
 * class, [requestURL] the affected request, and [reportedAt] the local
 * observation timestamp (ms epoch). No certificate error is ever fabricated.
 */
data class CertificateErrorModel(
    val eventId: Long = 0L,
    val errorType: String = "",
    val requestURL: String = "",
    val reportedAt: Long = 0L,
)

/**
 * Immutable model of the certificate chain reported by `Security.getCertificate`
 * for a given origin.
 *
 * [subjectName] / [issuer] identify the end-entity certificate subject and
 * issuer, [validFrom] / [validTo] its CDP `TimeSinceEpoch` validity bounds
 * (seconds), and [certificateChain] the reported X509 chain (as raw PEM
 * strings). All fields originate strictly from Chrome — none are fabricated
 * (No-Fake-Capability rule).
 */
data class CertificateModel(
    val subjectName: String = "",
    val issuer: String = "",
    val validFrom: Long = 0L,
    val validTo: Long = 0L,
    val certificateChain: List<String> = emptyList(),
)

/**
 * Availability of the `Security` CDP domain for the bound target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class SecurityCapabilityStatus {
    /** `Security` commands/notifications are functional on the attached target. */
    SUPPORTED,

    /** A `Security` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `Security` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** A `Security` command failed with an error. */
    FAILED,
}

/**
 * Outcome of a `Security` operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with a
 * value or resolves to an explicit [SecurityError] (`Unsupported`,
 * `Unavailable`, `ExecutionFailed`, `TargetDisconnected`). No mock or hardcoded
 * TLS / certificate data is ever produced (No-Fake-Capability rule).
 *
 * @param T The value carried on success (e.g. `Unit`).
 */
sealed interface SecurityActionResult<out T> {

    /** The security operation succeeded, carrying [value]. */
    data class Success<T>(val value: T) : SecurityActionResult<T>

    /** The security operation failed, carrying an explicit [error]. */
    data class Failure(val error: SecurityError) : SecurityActionResult<Nothing>
}

/**
 * Explicit, non-fabricated error for a failed `Security` operation.
 */
sealed interface SecurityError {

    /** A `Security` method is not available on the attached target. */
    data class Unsupported(val reason: String) : SecurityError

    /** The `Security` domain cannot be reached (e.g., no bound session). */
    data class Unavailable(val detail: String) : SecurityError

    /** The security command failed (protocol error, parsing error, ...). */
    data class ExecutionFailed(val cause: Throwable) : SecurityError

    /** The attached target disconnected while the operation was in flight. */
    class TargetDisconnected : SecurityError
}