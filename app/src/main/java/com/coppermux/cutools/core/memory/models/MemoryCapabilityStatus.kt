package com.coppermux.cutools.core.memory.models

/**
 * Availability of the `HeapProfiler` / `Memory` CDP domains for the bound target.
 *
 * No capability is ever fabricated: a genuine domain failure or an unavailable
 * method resolves to [UNSUPPORTED] / [UNAVAILABLE] / [FAILED] explicitly
 * (No-Fake-Capability rule).
 */
enum class MemoryCapabilityStatus {
    /** `HeapProfiler` / `Memory` commands are functional on the attached target. */
    SUPPORTED,

    /** A `HeapProfiler` / `Memory` method is not available on the connected target. */
    UNSUPPORTED,

    /** The `HeapProfiler` / `Memory` domain cannot be reached (e.g., no bound session). */
    UNAVAILABLE,

    /** A `HeapProfiler` / `Memory` command failed with an error. */
    FAILED,
}
