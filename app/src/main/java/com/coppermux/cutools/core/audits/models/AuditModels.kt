package com.coppermux.cutools.core.audits.models

/**
 * Classification of a genuine Chrome `Audits.issueAdded` issue code.
 *
 * Mapped strictly from the CDP `Audits.issue` wire `code` value. An
 * unrecognized but present wire code falls back to [GENERIC_ISSUE]; a missing /
 * null wire code resolves to [UNKNOWN] so an issue is never fabricated
 * (No-Fake-Capability rule).
 */
enum class AuditIssueCode {
    SAME_SITE_COOKIE,
    MIXED_CONTENT,
    BLOCKED_BY_RESPONSE,
    HEAVY_AD,
    CONTENT_SECURITY_POLICY,
    SHARED_ARRAY_BUFFER,
    ATTRIBUTION_REPORTING,
    GENERIC_ISSUE,
    UNKNOWN;

    companion object {
        /**
         * Maps a CDP `Audits.issue` wire `code` string to an [AuditIssueCode].
         * A null wire value resolves to [UNKNOWN]; an unrecognized non-null
         * value falls back to [GENERIC_ISSUE]. Never throws.
         */
        fun fromWire(value: String?): AuditIssueCode {
            if (value == null) return UNKNOWN
            val normalized = value.lowercase()
            return when {
                normalized.contains("samesitecookie") -> SAME_SITE_COOKIE
                normalized.contains("mixedcontent") -> MIXED_CONTENT
                normalized.contains("blockedbyresponse") -> BLOCKED_BY_RESPONSE
                normalized.contains("heavyad") -> HEAVY_AD
                normalized.contains("contentsecuritypolicy") -> CONTENT_SECURITY_POLICY
                normalized.contains("sharedarraybuffer") -> SHARED_ARRAY_BUFFER
                normalized.contains("attributionreporting") -> ATTRIBUTION_REPORTING
                else -> GENERIC_ISSUE
            }
        }
    }
}

/**
 * Immutable wrapper for a genuine Chrome `Audits.issueAdded` issue payload.
 *
 * [code] is the categorized issue type, [details] the flat map of issue detail
 * attributes Chrome reported (scalars only; nested objects are flattened into
 * their string representation), and [timestamp] the local observation
 * timestamp (ms epoch). No issue is ever fabricated (No-Fake-Capability).
 */
data class AuditIssueModel(
    val code: AuditIssueCode,
    val details: Map<String, String>,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Immutable wrapper for the genuine result of `Audits.checkContrast`.
 *
 * [nodeIds] are the DOM node ids Chrome reported (a single id is wrapped into
 * a singleton list), [contrastRatio] the measured contrast ratio,
 * [passesAA] / [passesAAA] whether the ratio meets the WCAG AA / AAA
 * thresholds per Chrome. No contrast state is ever fabricated.
 */
data class ContrastCheckResultModel(
    val nodeIds: List<Int>,
    val contrastRatio: Double,
    val passesAA: Boolean,
    val passesAAA: Boolean,
)

/**
 * Outcome of an `Audits` domain operation.
 *
 * Mirrors the CDP error-mapping contract: an operation either succeeds with a
 * value or resolves to an explicit [AuditError] (`Unsupported`,
 * `Unavailable`, `TargetDisconnected`, `ExecutionFailed`). No mock or
 * hardcoded audit state is ever produced (No-Fake-Capability rule).
 *
 * @param T The value carried on success.
 */
sealed class AuditActionResult<out T> {

    /** The audit operation succeeded, carrying [data]. */
    data class Success<out T>(val data: T) : AuditActionResult<T>()

    /** The audit operation failed, carrying an explicit [error]. */
    data class Failure(val error: AuditError) : AuditActionResult<Nothing>()
}

/**
 * Explicit, non-fabricated error for a failed `Audits` operation.
 */
sealed class AuditError {

    /** An `Audits` method is not available on the attached target. */
    object Unsupported : AuditError()

    /** The `Audits` domain cannot be reached (e.g., no bound session). */
    object Unavailable : AuditError()

    /** The attached target disconnected while the operation was in flight. */
    object TargetDisconnected : AuditError()

    /** The command failed (protocol error, parsing error, ...). */
    data class ExecutionFailed(val message: String) : AuditError()
}