package com.coppermux.cutools.core.connection

/**
 * Explicit connection state model.
 *
 * The UI derives connection status from this state rather than independently
 * tracking connection booleans.
 *
 * States:
 * - DISCONNECTED: No connection attempt.
 * - CONNECTING: Connection in progress.
 * - CONNECTED: WebSocket connected, ready for CDP communication.
 * - DISCOVERING_TARGETS: Enumerating Chrome targets.
 * - TARGET_SELECTED: A target has been selected, ready to attach.
 * - ATTACHING: Target attachment in progress.
 * - ATTACHED: Target session is active.
 * - ERROR: A recoverable or unrecoverable error has occurred.
 * - DISCONNECTING: Clean shutdown in progress.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCOVERING_TARGETS,
    TARGET_SELECTED,
    ATTACHING,
    ATTACHED,
    ERROR,
    DISCONNECTING,
    ;

    /** Whether the connection is in an active (non-terminal) state. */
    fun isActive(): Boolean = when (this) {
        CONNECTING, CONNECTED, DISCOVERING_TARGETS, TARGET_SELECTED,
        ATTACHING, ATTACHED -> true
        else -> false
    }

    /** Whether the connection allows sending commands. */
    fun canSendCommands(): Boolean = this == CONNECTED || this == ATTACHED
}
