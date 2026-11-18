package com.coppermux.cutools.core.accessibility.models

/**
 * Availability of the `Accessibility` CDP domain for the bound target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class AccessibilityCapabilityStatus {
    /** `Accessibility` commands/notifications are functional on the attached target. */
    SUPPORTED,

    /** An `Accessibility` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `Accessibility` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** An `Accessibility` command failed with an error. */
    FAILED,
}