package com.coppermux.cutools.core.target

/**
 * Represents a Chrome debugging target (tab, window, browser, etc.).
 *
 * A target model contains only fields supplied by Chrome. Unknown protocol fields
 * are not represented here but are not cause for a parsing failure.
 *
 * @property targetId Chrome target ID.
 * @property type Target type (e.g., "page", "browser", "service_worker", "devtools").
 * @property title Target title (may be empty for non-page targets).
 * @property url Target URL (may be empty).
 * @property webSocketDebuggerUrl WebSocket URL for attaching to this target's session.
 * @property faviconUrl Optional favicon URL.
 * @property attached Whether CuTools is currently attached to this target.
 * @property browserContextId Optional browser context ID.
 * @property projectId Optional project ID (Android-specific for webapk).
 */
data class TargetInfo(
    val targetId: String,
    val type: String,
    val title: String? = null,
    val url: String? = null,
    val webSocketDebuggerUrl: String? = null,
    val faviconUrl: String? = null,
    val attached: Boolean = false,
    val browserContextId: String? = null,
    val projectId: String? = null,
    val canSession: Boolean? = null,
    val canDisconnect: Boolean? = null,
    val description: String? = null,
    val isWindowAttached: Boolean? = null,
    val isVisible: Boolean? = null,
) {
    /** Convenience: is this a page-level target? */
    val isPage: Boolean get() = type == "page"

    /** Convenience: is this the browser target? */
    val isBrowser: Boolean get() = type == "browser"
}

/**
 * Represents an active CDP session for a target.
 *
 * When a target is attached (via Target.attachToTarget or the WebSocket URL),
 * Chrome returns a session ID that must be used for all subsequent commands
 * to that target.
 *
 * @property sessionId The CDP session ID.
 * @property targetId The target ID this session is associated with.
 * @property webSocketDebuggerUrl The WebSocket URL used to establish this session.
 */
data class SessionInfo(
    val sessionId: String,
    val targetId: String,
    val webSocketDebuggerUrl: String?,
)
