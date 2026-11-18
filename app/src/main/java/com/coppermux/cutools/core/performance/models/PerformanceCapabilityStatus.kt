package com.coppermux.cutools.core.performance.models

/**
 * Availability of the `Performance` CDP domain for the bound target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class PerformanceCapabilityStatus {
    /** `Performance` commands/notifications are functional on the attached target. */
    SUPPORTED,

    /** A `Performance` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `Performance` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** A `Performance` command failed with an error. */
    FAILED,
}