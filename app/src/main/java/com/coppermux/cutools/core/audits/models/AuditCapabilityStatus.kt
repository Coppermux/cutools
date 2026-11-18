package com.coppermux.cutools.core.audits.models

/**
 * Availability of the `Audits` CDP domain for the bound target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class AuditCapabilityStatus {
    /** `Audits` commands/notifications are functional on the attached target. */
    SUPPORTED,

    /** An `Audits` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `Audits` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** An `Audits` command failed with an error. */
    FAILED,
}