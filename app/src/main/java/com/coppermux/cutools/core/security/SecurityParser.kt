package com.coppermux.cutools.core.security

import com.coppermux.cutools.core.security.models.CertificateDetailsModel
import com.coppermux.cutools.core.security.models.CertificateErrorModel
import com.coppermux.cutools.core.security.models.CertificateModel
import com.coppermux.cutools.core.security.models.MixedContentType
import com.coppermux.cutools.core.security.models.SecurityExplanationModel
import com.coppermux.cutools.core.security.models.SecurityStateModel

/**
 * Parses genuine `Security` domain wire payloads into the typed security models
 * without fabricating any state.
 *
 * Pure and stateless so it is trivially unit-testable. Every method is
 * fail-safe: missing or malformed fields fall back to safe defaults and never
 * throw, so an undersized or hostile payload cannot corrupt engine state
 * (No-Fake-Capability rule).
 */
object SecurityParser {

    /**
     * Parses a single CDP `SecurityStateExplanation` entry (a JSON map) into a
     * [SecurityExplanationModel]. Returns null when [raw] is not a map. Only
     * genuinely-present fields are surfaced; malformed entries are tolerated.
     */
    fun parseExplanation(raw: Any?): SecurityExplanationModel? {
        if (raw !is Map<*, *>) return null
        val rawCertificate = raw["certificate"]
        val rawRecommendations = raw["recommendations"]
        return SecurityExplanationModel(
            securityState = SecurityStateModel.fromWire(raw["securityState"] as? String),
            title = (raw["title"] as? String) ?: "",
            summary = (raw["summary"] as? String) ?: "",
            description = (raw["description"] as? String) ?: "",
            hasCertificate = (raw["hasCertificate"] as? Boolean) ?: false,
            mixedContentType = MixedContentType.fromWire(raw["mixedContentType"] as? String),
            certificate = parseStringList(rawCertificate),
            recommendations = parseStringList(rawRecommendations),
        )
    }

    /**
     * Parses a raw CDP `CertificateSecurityState` object into a
     * [CertificateDetailsModel]. Returns null when [raw] is not a map. Only
     * genuinely-present fields are surfaced; missing optional fields default
     * safely (No-Fake-Capability rule).
     */
    fun parseCertificateDetails(raw: Any?): CertificateDetailsModel? {
        if (raw !is Map<*, *>) return null
        return CertificateDetailsModel(
            subjectName = (raw["subjectName"] as? String) ?: "",
            issuer = (raw["issuer"] as? String) ?: "",
            validFrom = (raw["validFrom"] as? Number)?.toDouble() ?: 0.0,
            validTo = (raw["validTo"] as? Number)?.toDouble() ?: 0.0,
            protocol = (raw["protocol"] as? String) ?: "",
            cipher = (raw["cipher"] as? String) ?: "",
            keyExchange = (raw["keyExchange"] as? String) ?: "",
            keyExchangeGroup = raw["keyExchangeGroup"] as? String,
            certificate = parseStringList(raw["certificate"]),
        )
    }

    /**
     * Parses a raw `Security.certificateError` event params map into a
     * [CertificateErrorModel]. Returns null when [raw] is not a map or carries no
     * genuine error signal.
     */
    fun parseCertificateError(raw: Any?): CertificateErrorModel? {
        if (raw !is Map<*, *>) return null
        val errorType = (raw["errorType"] as? String) ?: ""
        val requestURL = (raw["requestURL"] as? String) ?: ""
        if (errorType.isEmpty() && requestURL.isEmpty()) return null
        return CertificateErrorModel(
            eventId = (raw["eventId"] as? Number)?.toLong() ?: 0L,
            errorType = errorType,
            requestURL = requestURL,
            reportedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Extracts the active [SecurityStateModel] from a
     * `Security.securityStateChanged` event params map, or null when absent /
     * unrecognized. Unknown wire values resolve to null — no security state is
     * ever assumed.
     */
    fun parseSecurityState(raw: Any?): SecurityStateModel? {
        if (raw !is Map<*, *>) return null
        return SecurityStateModel.fromWire(raw["securityState"] as? String)
    }

    /**
     * Parses a `Security.getCertificate` response result object into a
     * [CertificateModel]. Returns null when [raw] is not a map, so the caller
     * can resolve an explicit failure. Only genuinely-present chain entries are
     * surfaced; missing optional fields default safely.
     */
    fun parseGetCertificate(raw: Any?): CertificateModel? {
        if (raw !is Map<*, *>) return null
        return CertificateModel(
            subjectName = (raw["subjectName"] as? String) ?: "",
            issuer = (raw["issuer"] as? String) ?: "",
            validFrom = (raw["validFrom"] as? Number)?.toLong() ?: 0L,
            validTo = (raw["validTo"] as? Number)?.toLong() ?: 0L,
            certificateChain = parseStringList(raw["certificateChain"]) + parseStringList(raw["certificate"]),
        )
    }

    private fun parseStringList(raw: Any?): List<String> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { it as? String }
    }
}