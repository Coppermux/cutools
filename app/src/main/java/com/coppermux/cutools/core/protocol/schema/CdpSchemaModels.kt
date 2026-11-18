package com.coppermux.cutools.core.protocol.schema

import com.squareup.moshi.Json

/**
 * Data model for the Chrome DevTools Protocol JSON schema (browser_protocol.json /
 * js_protocol.json). These models are used by [CdpSchemaValidator] to compare local
 * domain registrations against the upstream protocol definition.
 */
data class CdpProtocol(
    val domains: List<CdpDomain> = emptyList(),
)

/** A single CDP protocol domain (e.g. "CSS", "DOM", "Runtime"). */
data class CdpDomain(
    val domain: String = "",
    val experimental: Boolean = false,
    val deprecated: Boolean = false,
    val commands: List<CdpCommand> = emptyList(),
    val events: List<CdpEvent> = emptyList(),
    val types: List<CdpType> = emptyList(),
)

/** A CDP command (RPC method) declared within a domain. */
data class CdpCommand(
    val name: String = "",
    val parameters: List<CdpParameter> = emptyList(),
    val returns: List<CdpParameter> = emptyList(),
    val deprecated: Boolean = false,
)

/** A CDP event declared within a domain. */
data class CdpEvent(
    val name: String = "",
    val parameters: List<CdpParameter> = emptyList(),
    val deprecated: Boolean = false,
)

/** A named type (object / array / enum / primitive alias) declared within a domain. */
data class CdpType(
    val name: String = "",
    @Json(name = "type") val type: String? = null,
    @Json(name = "\$ref") val ref: String? = null,
    val properties: List<CdpParameter> = emptyList(),
    @Json(name = "enum") val enumValues: List<String> = emptyList(),
)

/** A parameter, property, or return entry declared in a command / event / type. */
data class CdpParameter(
    val name: String = "",
    @Json(name = "type") val type: String? = null,
    @Json(name = "\$ref") val ref: String? = null,
    val optional: Boolean = false,
    val deprecated: Boolean = false,
    @Json(name = "enum") val enumValues: List<String> = emptyList(),
)

/**
 * A command (or event) signature that CuTools registers locally and depends on.
 *
 * @property name Bare command/event name within its domain (e.g. "getMatchedStylesForNode").
 * @property requiredParameters Parameter names the local call site always sends.
 * @property optionalParameters Parameter names the local call site may send.
 */
data class LocalCommandSignature(
    val name: String = "",
    val requiredParameters: List<String> = emptyList(),
    val optionalParameters: List<String> = emptyList(),
) {
    val allParameters: Set<String> get() = (requiredParameters + optionalParameters).toSet()
}

/**
 * A local registration for one CDP domain.
 *
 * @property domain Domain name (e.g. "CSS").
 * @property commands Command signatures CuTools registers.
 * @property events Event names CuTools subscribes to.
 */
data class LocalDomainRegistration(
    val domain: String = "",
    val commands: List<LocalCommandSignature> = emptyList(),
    val events: List<String> = emptyList(),
)