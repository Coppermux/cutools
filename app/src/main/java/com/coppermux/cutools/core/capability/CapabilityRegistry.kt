package com.coppermux.cutools.core.capability

/**
 * Represents the availability of a Chrome/Chromium debugging capability.
 */
sealed interface CapabilityStatus {
    data object Supported : CapabilityStatus
    data object Unsupported : CapabilityStatus
    data object Experimental : CapabilityStatus
    data object Unavailable : CapabilityStatus

    /** The capability was probed but initialization failed. */
    data class Failed(val error: String) : CapabilityStatus
}

/**
 * Represents a single capability that can be queried.
 *
 * @property name Human-readable capability name.
 * @property status Current status.
 */
data class Capability(
    val name: String,
    val status: CapabilityStatus,
)

/**
 * Capability registry for tracking Chrome/Chromium protocol capabilities.
 *
 * This is the foundation infrastructure — later phases will register/query
 * tool-specific capabilities such as:
 * - Protocol domain availability
 * - Browser version
 * - Protocol version
 * - Target support
 * - Experimental feature support
 */
class CapabilityRegistry {
    private val capabilities = mutableMapOf<String, Capability>()

    /** Register or update a capability. */
    fun register(name: String, status: CapabilityStatus) {
        capabilities[name] = Capability(name, status)
    }

    /** Get the status of a capability, or null if not registered. */
    fun getStatus(name: String): CapabilityStatus? = capabilities[name]?.status

    /** Check if a capability is supported. */
    fun isSupported(name: String): Boolean = getStatus(name) is CapabilityStatus.Supported

    /** Get all registered capabilities. */
    fun getAll(): List<Capability> = capabilities.values.toList()

    /** Clear all capabilities. */
    fun clear() {
        capabilities.clear()
    }
}
