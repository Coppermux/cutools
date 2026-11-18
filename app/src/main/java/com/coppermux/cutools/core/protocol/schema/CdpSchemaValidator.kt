package com.coppermux.cutools.core.protocol.schema

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * The kind of protocol drift detected by [CdpSchemaValidator].
 */
enum class SchemaIssueKind {
    /** The upstream domain was removed entirely while a local registration remains. */
    REMOVED_DOMAIN,
    /** A locally-registered command is no longer declared upstream (breaking RPC signature change). */
    MISSING_COMMAND,
    /** A locally-registered event is no longer declared upstream. */
    MISSING_EVENT,
    /** A parameter the local call site requires is no longer declared upstream. */
    MISSING_PARAMETER,
    /** A type referenced by the upstream schema cannot be resolved within its domain. */
    UNRESOLVED_TYPE,
    /** A locally-registered command was marked deprecated upstream. */
    DEPRECATED_COMMAND,
    /** A locally-registered event was marked deprecated upstream. */
    DEPRECATED_EVENT,
    /** A parameter relied on by the local call site was marked deprecated upstream. */
    DEPRECATED_PARAMETER,
}

/**
 * A single drift-detection finding.
 */
data class SchemaIssue(
    val kind: SchemaIssueKind,
    val domain: String = "",
    val feature: String = "",
    val message: String = "",
)

/**
 * Counts of the registrations actually compared against the upstream schema.
 */
data class ValidationStats(
    val domainsChecked: Int = 0,
    val commandsChecked: Int = 0,
    val eventsChecked: Int = 0,
)

/**
 * Explicit outcome of a schema validation run.
 *
 * - [SchemaValidationResult.Success] — no mismatches or deprecations were detected.
 * - [SchemaValidationResult.Mismatches] — at least one breaking/removed feature was found.
 * - [SchemaValidationResult.Deprecations] — only non-breaking upstream deprecations were found.
 */
sealed interface SchemaValidationResult {
    data class Success(val stats: ValidationStats) : SchemaValidationResult
    data class Mismatches(val issues: List<SchemaIssue>) : SchemaValidationResult
    data class Deprecations(val issues: List<SchemaIssue>) : SchemaValidationResult
}

/**
 * Detects CDP protocol drift by comparing local domain registrations against the
 * upstream (Chromium) CDP JSON schema.
 *
 * The validator never throws an unhandled runtime exception for malformed input —
 * unparseable upstream JSON is treated as an empty comparison.
 */
class CdpSchemaValidator(
    initialRegistrations: List<LocalDomainRegistration> = emptyList(),
    moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build(),
) {
    private val registrations = mutableListOf<LocalDomainRegistration>()
    private val protocolAdapter = moshi.adapter(CdpProtocol::class.java).lenient()

    init {
        registrations.addAll(initialRegistrations)
    }

    /** Registers a local domain that CuTools depends on. */
    fun register(registration: LocalDomainRegistration) {
        registrations.add(registration)
    }

    /**
     * Validates local registrations against an upstream CDP JSON schema snapshot.
     * Unparseable input yields an empty Success result (nothing to compare).
     */
    fun validate(upstreamJson: String): SchemaValidationResult {
        if (registrations.isEmpty()) return SchemaValidationResult.Success(ValidationStats())
        val upstream = parse(upstreamJson)
            ?: return SchemaValidationResult.Success(ValidationStats())
        return validate(upstream)
    }

    /**
     * Validates local registrations against a parsed upstream [CdpProtocol].
     */
    fun validate(upstream: CdpProtocol): SchemaValidationResult {
        val mismatches = mutableListOf<SchemaIssue>()
        val deprecations = mutableListOf<SchemaIssue>()
        var domainsChecked = 0
        var commandsChecked = 0
        var eventsChecked = 0

        for (reg in registrations) {
            val upstreamDomain = upstream.domains.firstOrNull { it.domain == reg.domain }
            if (upstreamDomain == null) {
                mismatches.add(SchemaIssue(SchemaIssueKind.REMOVED_DOMAIN, reg.domain, "", "Local domain '${reg.domain}' is not declared upstream"))
                continue
            }
            domainsChecked += 1

            for (localCommand in reg.commands) {
                commandsChecked += 1
                val upstreamCommand = upstreamDomain.commands.firstOrNull { it.name == localCommand.name }
                if (upstreamCommand == null) {
                    mismatches.add(SchemaIssue(SchemaIssueKind.MISSING_COMMAND, reg.domain, localCommand.name, "Command ${reg.domain}.${localCommand.name} was removed or renamed upstream"))
                    continue
                }
                if (upstreamCommand.deprecated) {
                    deprecations.add(SchemaIssue(SchemaIssueKind.DEPRECATED_COMMAND, reg.domain, localCommand.name, "Command ${reg.domain}.${localCommand.name} is deprecated upstream"))
                }
                checkCommandParameters(reg.domain, localCommand, upstreamCommand.parameters, mismatches, deprecations)
                checkRequiredUpstreamParameters(localCommand, upstreamCommand, mismatches)
            }

            for (eventName in reg.events) {
                eventsChecked += 1
                val upstreamEvent = upstreamDomain.events.firstOrNull { it.name == eventName }
                if (upstreamEvent == null) {
                    mismatches.add(SchemaIssue(SchemaIssueKind.MISSING_EVENT, reg.domain, eventName, "Event ${reg.domain}.${eventName} was removed upstream"))
                    continue
                }
                if (upstreamEvent.deprecated) {
                    deprecations.add(SchemaIssue(SchemaIssueKind.DEPRECATED_EVENT, reg.domain, eventName, "Event ${reg.domain}.${eventName} is deprecated upstream"))
                }
                checkEventParameters(reg.domain, eventName, upstreamEvent.parameters, mismatches, deprecations)
            }

            checkTypeReferences(reg.domain, upstreamDomain, mismatches)
        }

        val stats = ValidationStats(domainsChecked, commandsChecked, eventsChecked)
        return when {
            mismatches.isNotEmpty() -> SchemaValidationResult.Mismatches(mismatches.toList())
            deprecations.isNotEmpty() -> SchemaValidationResult.Deprecations(deprecations.toList())
            else -> SchemaValidationResult.Success(stats)
        }
    }

    private fun checkCommandParameters(
        domain: String,
        local: LocalCommandSignature,
        upstreamParameters: List<CdpParameter>,
        mismatches: MutableList<SchemaIssue>,
        deprecations: MutableList<SchemaIssue>,
    ) {
        for (param in local.requiredParameters) {
            val upstreamParam = upstreamParameters.firstOrNull { it.name == param }
            if (upstreamParam == null) {
                mismatches.add(SchemaIssue(SchemaIssueKind.MISSING_PARAMETER, domain, local.name, "Local call site requires parameter '${param}' which is no longer declared upstream"))
            } else if (upstreamParam.deprecated) {
                deprecations.add(SchemaIssue(SchemaIssueKind.DEPRECATED_PARAMETER, domain, local.name, "Parameter '${param}' of ${domain}.${local.name} is deprecated upstream"))
            }
        }
    }

    private fun checkEventParameters(
        domain: String,
        eventName: String,
        upstreamParameters: List<CdpParameter>,
        mismatches: MutableList<SchemaIssue>,
        deprecations: MutableList<SchemaIssue>,
    ) {
        // Event payload drift is reported when any upstream parameter is deprecated.
        for (param in upstreamParameters) {
            if (param.deprecated) {
                deprecations.add(SchemaIssue(SchemaIssueKind.DEPRECATED_PARAMETER, domain, eventName, "Event parameter '${param.name}' of ${domain}.${eventName} is deprecated upstream"))
            }
        }
    }

    private fun checkRequiredUpstreamParameters(
        local: LocalCommandSignature,
        upstream: CdpCommand,
        mismatches: MutableList<SchemaIssue>,
    ) {
        // A parameter the upstream now requires but the local call site does not send
        // is a breaking signature change for the local caller.
        for (param in upstream.parameters) {
            if (param.optional) continue
            if (param.name !in local.allParameters) {
                mismatches.add(SchemaIssue(SchemaIssueKind.MISSING_PARAMETER, "", local.name, "Local command ${local.name} no longer supplies required upstream parameter '${param.name}'"))
            }
        }
    }

    private fun checkTypeReferences(
        domain: String,
        upstreamDomain: CdpDomain,
        mismatches: MutableList<SchemaIssue>,
    ) {
        for (type in upstreamDomain.types) {
            for (ref in collectRefs(type)) {
                if (!upstreamDomain.types.any { it.name == ref }) {
                    mismatches.add(SchemaIssue(SchemaIssueKind.UNRESOLVED_TYPE, domain, type.name, "Type '${ref}' referenced by ${domain}.${type.name} is not declared within domain ${domain}"))
                }
            }
        }
    }

    private fun collectRefs(type: CdpType): List<String> {
        val result = mutableListOf<String>()
        if (type.ref != null) result.add(type.ref)
        for (prop in type.properties) {
            if (prop.ref != null) result.add(prop.ref)
        }
        return result
    }

    private fun parse(upstreamJson: String): CdpProtocol? = try {
        protocolAdapter.fromJson(upstreamJson)
    } catch (e: Exception) {
        null
    }
}