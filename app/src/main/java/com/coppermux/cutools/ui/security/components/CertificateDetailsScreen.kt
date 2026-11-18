package com.coppermux.cutools.ui.security.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coppermux.cutools.core.security.models.SecurityCapabilityStatus
import com.coppermux.cutools.core.security.models.SecurityExplanationModel
import com.coppermux.cutools.core.security.models.SecurityStateModel
import com.coppermux.cutools.ui.security.SecurityViewModel

/**
 * Phase 13 — Security inspection & certificate details screen.
 *
 * Renders the real-time `Security.securityStateChanged` stream through the
 * shared [SecurityViewModel], a live security-state banner (SECURE / INSECURE /
 * NEUTRAL / INFO / UNKNOWN), security explanation cards, the active SSL/TLS
 * certificate chain, and a full certificate inspector (Subject, Issuer,
 * validity window, raw chain) driven by `Security.getCertificate`. An explicit
 * capability banner surfaces every engine failure; no TLS / certificate state is
 * ever fabricated (No-Fake-Capability rule).
 */
@Composable
fun CertificateDetailsScreen(
    viewModel: SecurityViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val securityState by viewModel.securityStateStream.collectAsStateWithLifecycle()
    var origin by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        capabilityBanner(state.status, state.errorMessage)?.let {
            SecurityBanner(text = it)
        }

        SectionHeader("Security domain")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Security notifications", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.enabled,
                enabled = viewModel.isAttached,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        viewModel.enableSecurity()
                    } else {
                        viewModel.disableSecurity()
                    }
                },
            )
        }
        OutlinedButton(
            onClick = { viewModel.refreshSecurityState() },
            enabled = viewModel.isAttached && !state.isLoading,
        ) {
            Text(if (state.isLoading) "Refreshing…" else "Refresh security state")
        }

        HorizontalDivider()

        SectionHeader("Security state")
        SecurityStateBadge(securityState = securityState)

        if (state.explanations.isNotEmpty()) {
            HorizontalDivider()
            SectionHeader("Security explanations")
            state.explanations.forEach { explanation ->
                ExplanationCard(explanation = explanation)
            }
        }

        if (state.certificateDetails != null) {
            HorizontalDivider()
            SectionHeader("Active certificate")
            CertificateSummaryCard(details = state.certificateDetails!!)
        }

        if (state.certificateErrors.isNotEmpty()) {
            HorizontalDivider()
            SectionHeader("Certificate errors")
            state.certificateErrors.forEach { error ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = "${error.errorType} · ${error.requestURL}",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        HorizontalDivider()

        SectionHeader("Certificate inspector")
        OutlinedTextField(
            value = origin,
            onValueChange = { origin = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Origin (e.g. https://example.com)") },
            singleLine = true,
        )
        Button(
            onClick = {
                if (origin.isNotBlank()) {
                    viewModel.fetchCertificate(origin.trim())
                }
            },
            enabled = viewModel.isAttached && origin.isNotBlank() && !state.isLoading,
        ) {
            Text("Fetch certificate")
        }
        state.certificate?.let { certificate ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Subject: ${certificate.subjectName}", style = MaterialTheme.typography.bodyMedium)
                    Text("Issuer: ${certificate.issuer}", style = MaterialTheme.typography.bodyMedium)
                    Text("Valid from: ${formatTimestamp(certificate.validFrom)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Valid to: ${formatTimestamp(certificate.validTo)}", style = MaterialTheme.typography.bodyMedium)
                    certificate.certificateChain.forEach { pem ->
                        Text(
                            text = pem,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityStateBadge(securityState: SecurityStateModel?) {
    val badge = when (securityState) {
        SecurityStateModel.SECURE -> BadgeSpec("SECURE", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        SecurityStateModel.INSECURE -> BadgeSpec("INSECURE", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
        SecurityStateModel.NEUTRAL -> BadgeSpec("NEUTRAL", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        SecurityStateModel.INFO -> BadgeSpec("INFO", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        SecurityStateModel.UNKNOWN -> BadgeSpec("UNKNOWN", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        null -> BadgeSpec("NO STATE", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = badge.color,
        contentColor = badge.contentColor,
    ) {
        Text(
            text = badge.label,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private data class BadgeSpec(
    val label: String,
    val color: androidx.compose.ui.graphics.Color,
    val contentColor: androidx.compose.ui.graphics.Color,
)

@Composable
private fun ExplanationCard(explanation: SecurityExplanationModel) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(explanation.title.ifEmpty { "(untitled explanation)" }, style = MaterialTheme.typography.titleSmall)
            if (explanation.summary.isNotBlank()) {
                Text(explanation.summary, style = MaterialTheme.typography.bodyMedium)
            }
            if (explanation.description.isNotBlank()) {
                Text(explanation.description, style = MaterialTheme.typography.bodySmall)
            }
            val badgeParts = mutableListOf<String>()
            explanation.securityState?.let { badgeParts.add(it.name) }
            explanation.mixedContentType?.let { badgeParts.add(it.name) }
            if (explanation.hasCertificate) badgeParts.add("hasCertificate")
            if (badgeParts.isNotEmpty()) {
                Text(badgeParts.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
            if (explanation.recommendations.isNotEmpty()) {
                Text("Recommendations: ${explanation.recommendations.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CertificateSummaryCard(details: com.coppermux.cutools.core.security.models.CertificateDetailsModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Subject: ${details.subjectName.ifEmpty { "—" }}", style = MaterialTheme.typography.bodyMedium)
        Text("Issuer: ${details.issuer.ifEmpty { "—" }}", style = MaterialTheme.typography.bodyMedium)
        Text("Protocol: ${details.protocol.ifEmpty { "—" }}", style = MaterialTheme.typography.bodyMedium)
        Text("Cipher: ${details.cipher.ifEmpty { "—" }}", style = MaterialTheme.typography.bodyMedium)
        Text("Key exchange: ${details.keyExchange.ifEmpty { "—" }}", style = MaterialTheme.typography.bodyMedium)
        details.certificate.forEach { pem ->
            Text(
                text = pem,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun SecurityBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatTimestamp(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "—"
    val millis = epochSeconds * 1000L
    val date = java.util.Date(millis)
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(date)
}

/**
 * Derives an explicit banner text for a security capability status, or null
 * when the domain is genuinely supported and no error is pending.
 */
internal fun capabilityBanner(
    status: SecurityCapabilityStatus,
    errorMessage: String?,
): String? {
    val banner = errorMessage ?: when (status) {
        SecurityCapabilityStatus.UNSUPPORTED ->
            "Unsupported: the Security domain is not available on this target."
        SecurityCapabilityStatus.UNAVAILABLE ->
            "Unavailable: attach a target to inspect TLS security."
        SecurityCapabilityStatus.FAILED ->
            "Failed: the Security domain reported an error."
        SecurityCapabilityStatus.SUPPORTED -> null
    }
    return banner
}