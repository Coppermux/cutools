package com.coppermux.cutools.core.emulation.models

/**
 * Availability of the `Emulation` CDP domain for the bound target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class EmulationCapabilityStatus {
    /** `Emulation` commands are functional on the attached target. */
    SUPPORTED,

    /** An `Emulation` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `Emulation` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** An `Emulation` command failed with an error. */
    FAILED,
}
