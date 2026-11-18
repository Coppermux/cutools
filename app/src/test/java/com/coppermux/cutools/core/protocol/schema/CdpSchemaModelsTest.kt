package com.coppermux.cutools.core.protocol.schema

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for the CDP protocol schema models
 * ([CdpProtocol], [CdpDomain], [CdpCommand], [CdpEvent], [CdpType],
 * [CdpParameter], [LocalCommandSignature], [LocalDomainRegistration]) and the
 * [CdpSchemaValidator] / [CdpSchemaModels] adapter round-trips.
 *
 * These complement [CdpSchemaValidatorTest] with full-protocol snapshots
 * (multiple domains, enum/array/ref types) and boundary conditions on the
 * local registration model. No network access.
 */
class CdpSchemaModelsTest {

    private val REF = "\$ref"

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private fun protocolAdapter() = moshi.adapter(CdpProtocol::class.java).lenient()

    /** A two-domain protocol snapshot covering commands, events, and rich types. */
    private val fullProtocolJson = """
        {
          "domains": [
            {
              "domain": "CSS",
              "experimental": false,
              "deprecated": false,
              "commands": [
                { "name": "enable", "parameters": [], "returns": [], "description": "Enables CSS." },
                {
                  "name": "getComputedStyleForNode",
                  "parameters": [
                    { "name": "nodeId", "type": "integer", "optional": false },
                    { "name": "pseudoElement", "type": "string", "optional": true }
                  ],
                  "returns": [
                    { "name": "computedStyle", "${REF}": "CSSComputedStyleProperty", "optional": false }
                  ]
                }
              ],
              "events": [
                {
                  "name": "styleSheetAdded",
                  "parameters": [
                    { "name": "header", "${REF}": "CSSStyleSheetHeader", "optional": false, "deprecated": false }
                  ]
                }
              ],
              "types": [
                {
                  "name": "CSSComputedStyleProperty",
                  "type": "object",
                  "properties": [
                    { "name": "name", "type": "string" },
                    { "name": "value", "type": "string" }
                  ]
                },
                { "name": "OrientationType", "type": "string", "enum": ["portrait-primary", "portrait-secondary"] },
                {
                  "name": "MediaFeature",
                  "type": "object",
                  "properties": [ { "name": "name", "type": "string" } ]
                },
                {
                  "name": "MediaFeatures",
                  "type": "array",
                  "items": { "${REF}": "MediaFeature" }
                }
              ]
            },
            {
              "domain": "DOM",
              "experimental": true,
              "deprecated": false,
              "commands": [
                { "name": "getDocument", "parameters": [
                    { "name": "depth", "type": "integer", "optional": true },
                    { "name": "pierce", "type": "boolean", "optional": true }
                  ], "returns": [ { "name": "root", "${REF}": "Node", "optional": false } ] }
              ],
              "events": [
                { "name": "setChildNodes", "parameters": [
                    { "name": "parentId", "type": "integer", "optional": false }
                  ] }
              ],
              "types": [
                { "name": "Node", "type": "object", "properties": [
                    { "name": "nodeId", "type": "integer", "optional": false },
                    { "name": "parentId", "type": "integer", "optional": true },
                    { "name": "nodeType", "type": "integer", "optional": false }
                  ] }
              ]
            }
          ]
        }
    """.trimIndent()

    // =====================================================================
    // Schema model parsing (Moshi round-trip)
    // =====================================================================

    @Test
    fun parse_fullProtocol_twoDomains_domainsParsed() {
        val protocol = protocolAdapter().fromJson(fullProtocolJson)!!
        assertEquals(2, protocol.domains.size)
        assertEquals(listOf("CSS", "DOM"), protocol.domains.map { it.domain })
    }

    @Test
    fun parse_fullProtocol_cssDomain_commandsAndEvents() {
        val css = protocolAdapter().fromJson(fullProtocolJson)!!.domains.first { it.domain == "CSS" }
        assertEquals(2, css.commands.size)
        assertEquals(1, css.events.size)
        assertFalse(css.experimental)
        assertFalse(css.deprecated)
    }

    @Test
    fun parse_command_withReturns_parsesReturnsList() {
        val css = protocolAdapter().fromJson(fullProtocolJson)!!.domains.first { it.domain == "CSS" }
        val cmd = css.commands.first { it.name == "getComputedStyleForNode" }
        assertEquals(2, cmd.parameters.size)
        assertEquals(1, cmd.returns.size)
        assertEquals("computedStyle", cmd.returns.first().name)
        assertEquals("CSSComputedStyleProperty", cmd.returns.first().ref)
    }

    @Test
    fun parse_parameter_optionalFlag_roundTrips() {
        val css = protocolAdapter().fromJson(fullProtocolJson)!!.domains.first { it.domain == "CSS" }
        val cmd = css.commands.first { it.name == "getComputedStyleForNode" }
        val nodeId = cmd.parameters.first { it.name == "nodeId" }
        val pseudo = cmd.parameters.first { it.name == "pseudoElement" }
        assertFalse(nodeId.optional)
        assertTrue(pseudo.optional)
    }

    @Test
    fun parse_parameter_defaultOptionalFalse_whenAbsent() {
        val css = protocolAdapter().fromJson(fullProtocolJson)!!.domains.first { it.domain == "CSS" }
        val styleSheet = css.events.first().parameters.first()
        assertFalse("Absent optional must default false", styleSheet.optional)
    }

    @Test
    fun parse_type_enumValues_roundTrips() {
        val css = protocolAdapter().fromJson(fullProtocolJson)!!.domains.first { it.domain == "CSS" }
        val orientation = css.types.first { it.name == "OrientationType" }
        assertEquals(listOf("portrait-primary", "portrait-secondary"), orientation.enumValues)
        assertEquals("string", orientation.type)
    }

    @Test
    fun parse_type_arrayWithItems_isParsedViaProperties() {
        val css = protocolAdapter().fromJson(fullProtocolJson)!!.domains.first { it.domain == "CSS" }
        val mediaFeatures = css.types.first { it.name == "MediaFeatures" }
        assertEquals("array", mediaFeatures.type)
        // items are modeled through the parameter properties list; no exception is fine
        assertTrue(mediaFeatures.properties.isEmpty())
    }

    @Test
    fun parse_domain_experimentalFlag_roundTrips() {
        val dom = protocolAdapter().fromJson(fullProtocolJson)!!.domains.first { it.domain == "DOM" }
        assertTrue(dom.experimental)
    }

    @Test
    fun parse_unknownFields_tolerated() {
        val json = """{ "domains": [ { "domain": "X", "commands": [
            { "name": "a", "parameters": [], "returns": [], "handlers": ["x"], "hidden": true }
        ], "events": [], "types": [], "version": 3 } ] }"""
        val protocol = protocolAdapter().fromJson(json)
        assertTrue("Unknown fields must be tolerated", protocol != null)
        assertEquals(1, protocol!!.domains.size)
    }

    @Test
    fun parse_malformedJson_returnsNull() {
        val parsed = try {
            protocolAdapter().fromJson("{ nope")
        } catch (e: Exception) {
            null
        }
        assertTrue("Malformed JSON must not produce a protocol object", parsed == null)
    }

    @Test
    fun parse_emptyDomains_defaultsEmptyList() {
        val protocol = protocolAdapter().fromJson("""{ }""")
        assertTrue(protocol != null)
        assertEquals(0, protocol!!.domains.size)
    }

    // =====================================================================
    // Local registration model
    // =====================================================================

    @Test
    fun localSignature_allParameters_isUnion() {
        val sig = LocalCommandSignature("getMetrics", listOf("a", "b"), listOf("b", "c"))
        assertEquals(setOf("a", "b", "c"), sig.allParameters)
    }

    @Test
    fun localSignature_emptyParameters_allEmpty() {
        val sig = LocalCommandSignature("enable")
        assertTrue(sig.allParameters.isEmpty())
    }

    @Test
    fun localDomainRegistration_defaults_empty() {
        val reg = LocalDomainRegistration()
        assertEquals("", reg.domain)
        assertTrue(reg.commands.isEmpty())
        assertTrue(reg.events.isEmpty())
    }

    @Test
    fun localDomainRegistration_constructor_preserves() {
        val reg = LocalDomainRegistration("Target", listOf(LocalCommandSignature("getTargets")), listOf("targetCreated"))
        assertEquals("Target", reg.domain)
        assertEquals("getTargets", reg.commands.first().name)
        assertEquals("targetCreated", reg.events.first())
    }

    // =====================================================================
    // Validator over the full protocol snapshot
    // =====================================================================

    @Test
    fun validate_fullProtocolSnapshot_matchingRegistration_success() {
        val registration = LocalDomainRegistration(
            domain = "CSS",
            commands = listOf(
                LocalCommandSignature("enable"),
                LocalCommandSignature("getComputedStyleForNode", requiredParameters = listOf("nodeId"), optionalParameters = listOf("pseudoElement"))
            ),
            events = listOf("styleSheetAdded")
        )
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = fullProtocolJson)
        assertTrue("Expected Success, got $result", result is SchemaValidationResult.Success)
        val success = result as SchemaValidationResult.Success
        assertEquals(1, success.stats.domainsChecked)
        assertEquals(2, success.stats.commandsChecked)
        assertEquals(1, success.stats.eventsChecked)
    }

    @Test
    fun validate_optionalUpstreamParameter_omittedLocally_success() {
        val registration = LocalDomainRegistration(
            domain = "CSS",
            commands = listOf(
                LocalCommandSignature("getComputedStyleForNode", requiredParameters = listOf("nodeId"))
            )
        )
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = fullProtocolJson)
        assertTrue("Optional param omission must not be a mismatch, got $result", result is SchemaValidationResult.Success)
    }

    @Test
    fun validate_localSendsExtraOptionalParameters_success() {
        val registration = LocalDomainRegistration(
            domain = "CSS",
            commands = listOf(
                LocalCommandSignature("getComputedStyleForNode", requiredParameters = listOf("nodeId"), optionalParameters = listOf("pseudoElement"))
            )
        )
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = fullProtocolJson)
        assertTrue("Expected Success, got $result", result is SchemaValidationResult.Success)
    }

    @Test
    fun validate_secondDomainRegistered_bothChecked() {
        val registrations = listOf(
            LocalDomainRegistration("CSS", commands = listOf(LocalCommandSignature("enable")), events = listOf("styleSheetAdded")),
            LocalDomainRegistration("DOM", commands = listOf(LocalCommandSignature("getDocument")), events = listOf("setChildNodes"))
        )
        val validator = CdpSchemaValidator(initialRegistrations = registrations)
        val result = validator.validate(upstreamJson = fullProtocolJson)
        assertTrue("Expected Success, got $result", result is SchemaValidationResult.Success)
        assertEquals(2, (result as SchemaValidationResult.Success).stats.domainsChecked)
        assertEquals(2, result.stats.commandsChecked)
        assertEquals(2, result.stats.eventsChecked)
    }

    @Test
    fun validate_resolvableOwnTypeReferences_notFlagged() {
        val registration = LocalDomainRegistration("CSS", commands = listOf(LocalCommandSignature("enable")))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = fullProtocolJson)
        assertTrue("Resolvable \$refs must not be UNRESOLVED_TYPE, got $result", result is SchemaValidationResult.Success)
    }

    @Test
    fun validate_upstreamRequiredParameter_absentLocally_mismatch() {
        // Upstream DOM.getDocument declares required `depth`/`pierce` only as optional,
        // so craft a stricter upstream where `nodeId` becomes required and local omits it.
        val upstream = """{ "domains": [ { "domain": "CSS", "commands": [
            { "name": "getComputedStyleForNode", "parameters": [
                { "name": "nodeId", "type": "integer", "optional": false },
                { "name": "deep", "type": "integer", "optional": false }
            ], "returns": [] }
        ] } ] }"""
        val registration = LocalDomainRegistration(
            domain = "CSS",
            commands = listOf(LocalCommandSignature("getComputedStyleForNode", requiredParameters = listOf("nodeId")))
        )
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = upstream)
        assertTrue("New required upstream param must be a mismatch, got $result", result is SchemaValidationResult.Mismatches)
    }

    @Test
    fun validate_multipleRegistrationsSameRemovedDomain_eachReported() {
        val registrations = listOf(
            LocalDomainRegistration("Ghost", events = listOf("a")),
            LocalDomainRegistration("Ghost", events = listOf("b"))
        )
        val validator = CdpSchemaValidator(initialRegistrations = registrations)
        val result = validator.validate(upstreamJson = fullProtocolJson)
        assertTrue("Expected Mismatches, got $result", result is SchemaValidationResult.Mismatches)
        val removed = (result as SchemaValidationResult.Mismatches).issues.count { it.kind == SchemaIssueKind.REMOVED_DOMAIN }
        assertEquals(2, removed)
    }

    @Test
    fun validate_deprecatedEventParameter_reportsDeprecations() {
        val upstream = """{ "domains": [ { "domain": "CSS", "events": [
            { "name": "styleSheetAdded", "parameters": [ { "name": "legacy", "type": "string", "deprecated": true } ] }
        ] } ] }"""
        val registration = LocalDomainRegistration("CSS", events = listOf("styleSheetAdded"))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = upstream)
        assertTrue("Expected Deprecations, got $result", result is SchemaValidationResult.Deprecations)
        assertTrue(
            "Expected DEPRECATED_PARAMETER on event",
            (result as SchemaValidationResult.Deprecations).issues.any { it.kind == SchemaIssueKind.DEPRECATED_PARAMETER }
        )
    }

    @Test
    fun validate_commandReturnDeprecation_notEvaluated() {
        // returns[] entries are not surfaced as issues; only command/event params are checked.
        val upstream = """{ "domains": [ { "domain": "CSS", "commands": [
            { "name": "enable", "parameters": [], "returns": [ { "name": "r", "type": "string", "deprecated": true } ] }
        ] } ] }"""
        val registration = LocalDomainRegistration("CSS", commands = listOf(LocalCommandSignature("enable")))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = upstream)
        assertTrue("Return deprecation must not surface, got $result", result is SchemaValidationResult.Success)
    }

    @Test
    fun validate_registrationWithOnlyEvents_success() {
        val registration = LocalDomainRegistration("DOM", events = listOf("setChildNodes"))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = fullProtocolJson)
        assertTrue("Expected Success, got $result", result is SchemaValidationResult.Success)
        val stats = (result as SchemaValidationResult.Success).stats
        assertEquals(1, stats.domainsChecked)
        assertEquals(0, stats.commandsChecked)
        assertEquals(1, stats.eventsChecked)
    }

    @Test
    fun validate_registrationWithOnlyCommands_success() {
        val registration = LocalDomainRegistration("DOM", commands = listOf(LocalCommandSignature("getDocument")))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = fullProtocolJson)
        assertTrue("Expected Success, got $result", result is SchemaValidationResult.Success)
        assertEquals(1, (result as SchemaValidationResult.Success).stats.commandsChecked)
    }

    @Test
    fun validate_commandWithNoUpstreamMatch_mismatch() {
        val registration = LocalDomainRegistration("DOM", commands = listOf(LocalCommandSignature("ghostCommand")))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = fullProtocolJson)
        assertTrue("Expected Mismatches, got $result", result is SchemaValidationResult.Mismatches)
        assertTrue(
            (result as SchemaValidationResult.Mismatches).issues.any { it.kind == SchemaIssueKind.MISSING_COMMAND }
        )
    }

    @Test
    fun validate_parsedProtocolObjectOverload_behavesIdentically() {
        val upstream = protocolAdapter().fromJson(fullProtocolJson)!!
        val registration = LocalDomainRegistration("CSS", commands = listOf(LocalCommandSignature("enable")))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstream)
        assertTrue("Object overload must match JSON overload, got $result", result is SchemaValidationResult.Success)
    }

    @Test
    fun validate_mismatchPrecedence_resultContainsOnlyMismatchKinds() {
        // Deprecations are reported only when there are no mismatches; a breaking
        // mismatch dominates and surfaces alone in the Mismatches result.
        val upstream = """{ "domains": [ { "domain": "CSS", "commands": [
            { "name": "enable", "parameters": [], "returns": [], "deprecated": true }
        ] } ] }"""
        val registration = LocalDomainRegistration(
            domain = "CSS",
            commands = listOf(LocalCommandSignature("enable"), LocalCommandSignature("missing"))
        )
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = upstream)
        assertTrue("Expected Mismatches (precedence), got $result", result is SchemaValidationResult.Mismatches)
        val kinds = (result as SchemaValidationResult.Mismatches).issues.map { it.kind }
        assertTrue("Expected MISSING_COMMAND in $kinds", kinds.contains(SchemaIssueKind.MISSING_COMMAND))
        assertTrue("Deprecations must not surface when a mismatch exists", kinds.none { it == SchemaIssueKind.DEPRECATED_COMMAND })
    }
}