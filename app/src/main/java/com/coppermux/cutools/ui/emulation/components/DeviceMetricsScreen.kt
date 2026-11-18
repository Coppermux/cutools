package com.coppermux.cutools.ui.emulation.components

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
import com.coppermux.cutools.core.emulation.models.DeviceMetricsModel
import com.coppermux.cutools.core.emulation.models.EmulationCapabilityStatus
import com.coppermux.cutools.core.emulation.models.ScreenOrientationModel
import com.coppermux.cutools.core.emulation.models.UserAgentOverrideModel
import com.coppermux.cutools.ui.emulation.EmulationViewModel

/**
 * Phase 9C — Device metrics & user-agent emulation screen.
 *
 * Provides device metric preset selection (Mobile / Tablet / Desktop viewports),
 * custom dimension / scale factor / orientation inputs, user-agent preset and
 * custom string controls, and native-metrics restoration. Every control
 * dispatches a genuine `Emulation` command through the shared
 * [EmulationViewModel]; capability failures surface as an explicit banner and
 * no override state is fabricated (No-Fake-Capability rule).
 */
@Composable
fun DeviceMetricsScreen(
    viewModel: EmulationViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var customWidth by rememberSaveable { mutableStateOf("390") }
    var customHeight by rememberSaveable { mutableStateOf("844") }
    var customScale by rememberSaveable { mutableStateOf("3.0") }
    var customMobile by rememberSaveable { mutableStateOf(true) }
    var customUa by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        capabilityBanner(state.status, state.errorMessage)?.let {
            EmulationBanner(text = it)
        }

        SectionHeader("Active device metrics")
        Text(
            text = state.activeMetrics?.let { "${it.width}x${it.height} @${it.deviceScaleFactor}" } ?: "Native viewport",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionHeader("Device presets")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EmulationViewModel.DEVICE_PRESETS.forEach { (label, preset) ->
                OutlinedButton(
                    onClick = { viewModel.applyDevicePreset(preset) },
                    enabled = viewModel.isAttached,
                ) {
                    Text(label)
                }
            }
        }
        OutlinedButton(
            onClick = { viewModel.clearDeviceMetrics() },
            enabled = viewModel.isAttached && state.activeMetrics != null,
        ) {
            Text("Clear device metrics")
        }

        SectionHeader("Custom metrics")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = customWidth,
                onValueChange = { customWidth = it },
                modifier = Modifier.weight(1f),
                label = { Text("Width") },
                singleLine = true,
            )
            OutlinedTextField(
                value = customHeight,
                onValueChange = { customHeight = it },
                modifier = Modifier.weight(1f),
                label = { Text("Height") },
                singleLine = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = customScale,
                onValueChange = { customScale = it },
                modifier = Modifier.weight(1f),
                label = { Text("Scale factor") },
                singleLine = true,
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("Mobile")
                Switch(
                    checked = customMobile,
                    onCheckedChange = { customMobile = it },
                )
            }
        }
        Button(
            onClick = {
                val width = customWidth.toIntOrNull()
                val height = customHeight.toIntOrNull()
                val scale = customScale.toDoubleOrNull()
                if (width != null && height != null && scale != null) {
                    viewModel.applyDevicePreset(
                        DeviceMetricsModel(
                            width = width,
                            height = height,
                            deviceScaleFactor = scale,
                            mobile = customMobile,
                            screenOrientation = if (customMobile) {
                                ScreenOrientationModel(type = "portraitPrimary", angle = 0)
                            } else {
                                null
                            },
                        )
                    )
                }
            },
            enabled = viewModel.isAttached,
        ) {
            Text("Apply custom metrics")
        }

        HorizontalDivider()

        SectionHeader("Active user agent")
        Text(
            text = state.activeUserAgent?.userAgent ?: "Native user agent",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
        )

        SectionHeader("User-agent presets")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EmulationViewModel.USER_AGENT_PRESETS.forEach { (label, ua) ->
                OutlinedButton(
                    onClick = { viewModel.setUserAgent(ua) },
                    enabled = viewModel.isAttached,
                ) {
                    Text(label)
                }
            }
        }

        SectionHeader("Custom user agent")
        OutlinedTextField(
            value = customUa,
            onValueChange = { customUa = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("User agent string") },
            singleLine = true,
        )
        Button(
            onClick = {
                if (customUa.isNotBlank()) {
                    viewModel.setUserAgent(UserAgentOverrideModel(userAgent = customUa))
                }
            },
            enabled = viewModel.isAttached && customUa.isNotBlank(),
        ) {
            Text("Apply user agent")
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
private fun EmulationBanner(text: String) {
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

/**
 * Derives an explicit banner text for an emulation capability status, or null
 * when the domain is genuinely supported and no error is pending.
 */
internal fun capabilityBanner(
    status: EmulationCapabilityStatus,
    errorMessage: String?,
): String? {
    val banner = errorMessage ?: when (status) {
        EmulationCapabilityStatus.UNSUPPORTED ->
            "Unsupported: the Emulation domain is not available on this target."
        EmulationCapabilityStatus.UNAVAILABLE ->
            "Unavailable: attach a target to apply emulation overrides."
        EmulationCapabilityStatus.FAILED ->
            "Failed: the Emulation domain reported an error."
        EmulationCapabilityStatus.SUPPORTED -> null
    }
    return banner
}