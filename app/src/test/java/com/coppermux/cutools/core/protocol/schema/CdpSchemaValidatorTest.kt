package com.coppermux.cutools.core.protocol.schema

import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic unit tests for [CdpSchemaValidator] — CDP protocol domain
 * parsing and upstream signature-drift detection.
 *
 * These never touch the network; they validate against known CDP JSON schema
 * snapshots embedded below.
 */
class CdpSchemaValidatorTest {

    private val REF = "\$ref"

    private val cssSnapshotJson = """
        {
          "domains": [
            {
              "domain": "CSS",
              "experimental": false,
              "types": [
                { "name": "SSRange", "type": "object", "properties": [ { "name": "startLine", "type": "integer" } ] }
              ],
              "commands": [
                { "name": "enable", "parameters": [], "returns": [], "description": "Enables the CSS domain." },
                {
                  "name": "getMatchedStylesForNode",
                  "parameters": [
                    { "name": "nodeId", "type": "integer", "optional": false },
                    { "name": "paintExact", "type": "boolean", "optional": true }
                  ],
                  "returns": []
                }
              ],
              "events": [
                { "name": "styleSheetAdded", "parameters": [ { "name": "styleSheet", "${REF}": "CSSStyleSheet", "optional": false } ] }
              ]
            }
          ]
        }
    """

    private fun cssRegistration(required: List<String> = listOf("nodeId"), optional: List<String> = emptyList()) =
        LocalDomainRegistration(
            domain = "CSS",
            commands = listOf(
                LocalCommandSignature("enable"),
                LocalCommandSignature("getMatchedStylesForNode", requiredParameters = required, optionalParameters = optional)
            ),
            events = listOf("styleSheetAdded")
        )

    // =====================================================================
    // Protocol domain parsing
    // =====================================================================

    @Test
    fun parse_upstreamJson_matchesLocalRegistration_returnsSuccess() {
        val validator = CdpSchemaValidator(initialRegistrations = listOf(cssRegistration()))
        val result = validator.validate(upstreamJson = cssSnapshotJson)
        assertTrue("Expected Success, got $result", result is SchemaValidationResult.Success)
        val success = result as SchemaValidationResult.Success
        assertEquals(1, success.stats.domainsChecked)
        assertEquals(2, success.stats.commandsChecked)
        assertEquals(1, success.stats.eventsChecked)
    }

    @Test
    fun parse_upstreamJson_withDescriptionFields_isTolerant() {
        // Real browser_protocol.json includes "description" on most nodes; the
        // validator must tolerate unknown fields without failing to parse.
        val validator = CdpSchemaValidator(initialRegistrations = listOf(cssRegistration()))
        val result = validator.validate(upstreamJson = cssSnapshotJson)
        assertTrue("Expected a parsed Success (not an exception), got $result", result is SchemaValidationResult.Success)
    }

    @Test
    fun validate_malformedJson_doesNotThrow() {
        val validator = CdpSchemaValidator(initialRegistrations = listOf(cssRegistration()))
        val result = validator.validate(upstreamJson = "{ this is not json")
        assertTrue("Malformed JSON must not throw; got $result", result is SchemaValidationResult.Success)
    }

    @Test
    fun validate_noRegistrations_returnsEmptySuccess() {
        val validator = CdpSchemaValidator()
        val result = validator.validate(upstreamJson = cssSnapshotJson)
        assertTrue("No registrations yields empty Success, got $result", result is SchemaValidationResult.Success)
        assertEquals(0, (result as SchemaValidationResult.Success).stats.commandsChecked)
    }

    @Test
    fun validate_registrationAddedAfterConstruction_isDetected() {
        val validator = CdpSchemaValidator()
        validator.register(cssRegistration())
        val result = validator.validate(upstreamJson = cssSnapshotJson)
        assertTrue("Expected Success after registration, got $result", result is SchemaValidationResult.Success)
    }

    // =====================================================================
    // Signature drift detection
    // =====================================================================

    @Test
    fun validate_removedDomain_reportsRemovedDomainMismatch() {
        val local = LocalDomainRegistration(domain = "DOM", events = listOf("documentUpdated"))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(local))
        val result = validator.validate(upstreamJson = cssSnapshotJson)
        assertTrue("Expected Mismatches, got $result", result is SchemaValidationResult.Mismatches)
        val issues = (result as SchemaValidationResult.Mismatches).issues
        assertTrue("Expected a REMOVED_DOMAIN issue", issues.any { it.kind == SchemaIssueKind.REMOVED_DOMAIN })
    }

    @Test
    fun validate_removedCommand_reportsMissingCommand() {
        val registration = LocalDomainRegistration(
            domain = "CSS",
            commands = listOf(LocalCommandSignature("getComputedStyleForNode"))
        )
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = cssSnapshotJson)
        assertTrue("Expected Mismatches, got $result", result is SchemaValidationResult.Mismatches)
        val issues = (result as SchemaValidationResult.Mismatches).issues
        assertTrue("Expected MISSING_COMMAND issue, got $issues", issues.any { it.kind == SchemaIssueKind.MISSING_COMMAND })
    }

    @Test
    fun validate_removedEvent_reportsMissingEvent() {
        val registration = LocalDomainRegistration(domain = "CSS", events = listOf("styleSheetRemoved"))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = cssSnapshotJson)
        assertTrue("Expected Mismatches, got $result", result is SchemaValidationResult.Mismatches)
        val issues = (result as SchemaValidationResult.Mismatches).issues
        assertTrue("Expected MISSING_EVENT issue, got $issues", issues.any { it.kind == SchemaIssueKind.MISSING_EVENT })
    }

    @Test
    fun validate_missingRequiredParameter_reportsMissingParameter() {
        val registration = LocalDomainRegistration(
            domain = "CSS",
            commands = listOf(
                LocalCommandSignature("getMatchedStylesForNode", requiredParameters = listOf("nodeId", "colorScheme"))
            )
        )
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = cssSnapshotJson)
        assertTrue("Expected Mismatches, got $result", result is SchemaValidationResult.Mismatches)
        val issues = (result as SchemaValidationResult.Mismatches).issues
        assertTrue(
            "Expected MISSING_PARAMETER for 'colorScheme', got $issues",
            issues.any { it.kind == SchemaIssueKind.MISSING_PARAMETER && it.feature == "getMatchedStylesForNode" }
        )
    }

    @Test
    fun validate_newRequiredUpstreamParameter_isBreaking() {
        // Upstream now requires a parameter the local call site does not send.
        val upstreamJson = """
            { "domains": [ { "domain": "CSS", "commands": [
                { "name": "enable", "parameters": [ { "name": "force", "type": "boolean", "optional": false } ], "returns": [] }
            ] } ] }
        """
        val registration = LocalDomainRegistration(domain = "CSS", commands = listOf(LocalCommandSignature("enable")))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = upstreamJson)
        assertTrue("Expected Mismatches, got $result", result is SchemaValidationResult.Mismatches)
        assertTrue(
            "Expected breaking required-parameter mismatch",
            (result as SchemaValidationResult.Mismatches).issues.any { it.kind == SchemaIssueKind.MISSING_PARAMETER }
        )
    }

    @Test
    fun validate_unresolvedTypeReference_reportsMismatch() {
        val upstreamJson = """
            { "domains": [ { "domain": "CSS", "types": [
                { "name": "SSRange", "type": "object", "properties": [ { "name": "next", "${REF}": "MissingType" } ] }
            ] } ] }
        """
        val validator = CdpSchemaValidator(initialRegistrations = listOf(LocalDomainRegistration("CSS")))
        val result = validator.validate(upstreamJson = upstreamJson)
        assertTrue("Expected Mismatches, got $result", result is SchemaValidationResult.Mismatches)
        assertTrue(
            "Expected UNRESOLVED_TYPE issue",
            (result as SchemaValidationResult.Mismatches).issues.any { it.kind == SchemaIssueKind.UNRESOLVED_TYPE }
        )
    }

    // =====================================================================
    // Deprecation detection
    // =====================================================================

    @Test
    fun validate_deprecatedCommand_reportsDeprecation() {
        val upstreamJson = """
            { "domains": [ { "domain": "CSS", "commands": [
                { "name": "enable", "parameters": [], "returns": [], "deprecated": true }
            ] } ] }
        """
        val registration = LocalDomainRegistration(domain = "CSS", commands = listOf(LocalCommandSignature("enable")))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = upstreamJson)
        assertTrue("Expected Deprecations, got $result", result is SchemaValidationResult.Deprecations)
        val issues = (result as SchemaValidationResult.Deprecations).issues
        assertTrue("Expected DEPRECATED_COMMAND issue, got $issues", issues.any { it.kind == SchemaIssueKind.DEPRECATED_COMMAND })
    }

    @Test
    fun validate_deprecatedEvent_reportsDeprecation() {
        val upstreamJson = """
            { "domains": [ { "domain": "CSS", "events": [
                { "name": "styleSheetAdded", "parameters": [], "deprecated": true }
            ] } ] }
        """
        val registration = LocalDomainRegistration(domain = "CSS", events = listOf("styleSheetAdded"))
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = upstreamJson)
        assertTrue("Expected Deprecations, got $result", result is SchemaValidationResult.Deprecations)
        assertTrue(
            "Expected DEPRECATED_EVENT issue",
            (result as SchemaValidationResult.Deprecations).issues.any { it.kind == SchemaIssueKind.DEPRECATED_EVENT }
        )
    }

    // =====================================================================
    // Precedence: mismatches dominate deprecations
    // =====================================================================

    @Test
    fun validate_mismatchTakesPrecedenceOverDeprecation() {
        val upstreamJson = """
            { "domains": [ { "domain": "CSS", "commands": [
                { "name": "enable", "parameters": [], "returns": [], "deprecated": true }
            ] } ] }
        """
        // Registers a command that does not exist upstream (mismatch) alongside
        // one that exists but is deprecated.
        val registration = LocalDomainRegistration(
            domain = "CSS",
            commands = listOf(LocalCommandSignature("enable"), LocalCommandSignature("ghost"))
        )
        val validator = CdpSchemaValidator(initialRegistrations = listOf(registration))
        val result = validator.validate(upstreamJson = upstreamJson)
        assertTrue("Mismatches must take precedence, got $result", result is SchemaValidationResult.Mismatches)
    }
}