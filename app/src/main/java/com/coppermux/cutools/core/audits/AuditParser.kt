package com.coppermux.cutools.core.audits

import com.coppermux.cutools.core.audits.models.AuditIssueCode
import com.coppermux.cutools.core.audits.models.AuditIssueModel
import com.coppermux.cutools.core.audits.models.ContrastCheckResultModel

/**
 * Pure, stateless, fail-safe parser for genuine CDP `Audits` domain wire
 * payloads.
 *
 * Parsing never deserializes into the typed models and never throws on missing
 * or malformed fields: issue `code` categorizes into an [AuditIssueCode]
 * (falling back to [AuditIssueCode.GENERIC_ISSUE] for unrecognized non-null
 * codes), a missing wire code yields no issue, and every optional field falls
 * back to a safe default. No audit state is ever fabricated (No-Fake-Capability).
 *
 * Threading: the parser is an immutable, stateless singleton; all methods are
 * pure so it is trivially safe to call from any thread.
 */
object AuditParser {

    /**
     * Parses a single CDP `Audits.issue` map (from the `Audits.issueAdded`
     * event `params.issue`) into an [AuditIssueModel]. Returns null when [raw]
     * is not a map or carries no genuine `code` signal (a null wire code
     * resolves to [AuditIssueCode.UNKNOWN] and is not surfaced). Never throws.
     */
    fun parseIssue(raw: Any?): AuditIssueModel? {
        if (raw !is Map<*, *>) return null
        val code = AuditIssueCode.fromWire(raw["code"] as? String)
        if (code == AuditIssueCode.UNKNOWN) return null
        return AuditIssueModel(
            code = code,
            details = parseDetails(raw["details"]),
        )
    }

    /**
     * Parses the raw result object of `Audits.checkContrast` (a JSON map
     * carrying `nodeIds` / `contrastRatio` / `passesAA` / `passesAAA`) into a
     * [ContrastCheckResultModel]. Returns null when [raw] is not a map. A
     * missing `nodeIds` array falls back to a singleton from `nodeId`, then to
     * empty. Never throws.
     */
    fun parseContrastResult(raw: Any?): ContrastCheckResultModel? {
        if (raw !is Map<*, *>) return null
        return ContrastCheckResultModel(
            nodeIds = parseNodeIds(raw),
            contrastRatio = (raw["contrastRatio"] as? Number)?.toDouble() ?: 0.0,
            passesAA = (raw["passesAA"] as? Boolean) ?: false,
            passesAAA = (raw["passesAAA"] as? Boolean) ?: false,
        )
    }

    private fun parseDetails(raw: Any?): Map<String, String> {
        if (raw !is Map<*, *>) return emptyMap()
        val out = linkedMapOf<String, String>()
        raw.forEach { (key, value) -> out[key.toString()] = scalarToString(value) }
        return out
    }

    private fun parseNodeIds(raw: Map<*, *>): List<Int> {
        val nodeIds = raw["nodeIds"]
        if (nodeIds is List<*>) {
            return nodeIds.mapNotNull { (it as? Number)?.toInt() }
        }
        val single = (raw["nodeId"] as? Number)?.toInt()
        return single?.let { listOf(it) } ?: emptyList()
    }

    private fun scalarToString(raw: Any?): String =
        raw as? String ?: raw?.toString() ?: ""
}