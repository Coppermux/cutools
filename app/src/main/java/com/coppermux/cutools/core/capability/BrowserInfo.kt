package com.coppermux.cutools.core.capability

/**
 * Represents basic browser information retrieved from Chrome.
 *
 * Retrieved via the CDP "Browser.getVersion" command.
 *
 * @property browser Browser name and version (e.g., "Chrome 151.0.7922.71").
 * @property protocolVersion CDP protocol version.
 * @property userAgent User agent string.
 * @property userAgentClientPlatform String representing the client platform.
 * @property userAgentMobile Whether the UA indicates a mobile device.
 * @property userAgentArchitecture Architecture string.
 * @property userAgentFormFactor Form factor hint.
 * @property product Product identification string.
 * @property protocolVersionMajor Major version of the protocol.
 * @property protocolVersionMinor Minor version of the protocol.
 */
data class BrowserInfo(
    val browser: String? = null,
    val protocolVersion: String? = null,
    val userAgent: String? = null,
    val userAgentClientPlatform: String? = null,
    val userAgentMobile: String? = null,
    val userAgentArchitecture: String? = null,
    val userAgentFormFactor: String? = null,
    val product: String? = null,
    val protocolVersionMajor: String? = null,
    val protocolVersionMinor: String? = null,
)
