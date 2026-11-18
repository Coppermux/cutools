package com.coppermux.cutools.ui.emulation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coppermux.cutools.core.emulation.models.EmulationCapabilityStatus
import com.coppermux.cutools.core.emulation.models.MediaFeatureModel
import com.coppermux.cutools.ui.emulation.EmulationViewModel

/**
 * Phase 9C — Network, touch, and media-feature emulation screen.
 *
 * Provides touch emulation toggling, emulated media type selection, media
 * feature overrides (`prefers-color-scheme`, `prefers-reduced-motion`), and a
 * CPU throttling slider (1x / 2x / 4x / 6x). Every control dispatches a genuine
 * `Emulation` command through the shared [EmulationViewModel]; capability
 * failures surface as an explicit banner and no override state is fabricated
 * (No-Fake-Capability rule).
 */
@Composable
fun NetworkEmulationScreen(
    viewModel: EmulationViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var touchEnabled by rememberSaveable { mutableStateOf(false) }
    var mediaSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var colorScheme by rememberSaveable { mutableStateOf<String?>(null) }
    var reducedMotion by rememberSaveable { mutableStateOf(false) }
    var throttleIndex by rememberSaveable { mutableIntStateOf(0) }

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

        SectionHeader("Touch emulation")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Touch events", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = touchEnabled,
                onCheckedChange = { enabled ->
                    touchEnabled = enabled
                    viewModel.toggleTouchEmulation(enabled)
                },
            )
        }

        HorizontalDivider()

        SectionHeader("Emulated media type")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("None" to null, "Screen" to "screen", "Print" to "print").forEach { (label, value) ->
                val selected = mediaSelectionMatches(mediaSelection, value)
                val selectedStyle = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    color = selectedStyle,
                ) {
                    OutlinedButton(
                        onClick = {
                            mediaSelection = if (mediaSelection == value) null else value
                            dispatchMediaOverrides(viewModel, mediaSelection, colorScheme, reducedMotion)
                        },
                    ) {
                        Text(label)
                    }
                }
            }
        }

        SectionHeader("Media features")
        ColorSchemeSelector(
            selected = colorScheme,
            onSelect = { selected ->
                colorScheme = selected
                dispatchMediaOverrides(viewModel, mediaSelection, colorScheme, reducedMotion)
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Prefers reduced motion", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = reducedMotion,
                onCheckedChange = { enabled ->
                    reducedMotion = enabled
                    dispatchMediaOverrides(viewModel, mediaSelection, colorScheme, reducedMotion)
                },
            )
        }

        HorizontalDivider()

        SectionHeader("CPU throttling")
        Text(
            text = "Slowdown: ${throttleLabel(state.cpuThrottlingRate)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = throttleIndex.toFloat(),
            onValueChange = {
                val index = it.toInt().coerceIn(0, EmulationViewModel.CPU_THROTTLE_RATES.size - 1)
                throttleIndex = index
                viewModel.setCPUThrottling(EmulationViewModel.CPU_THROTTLE_RATES[index])
            },
            steps = (EmulationViewModel.CPU_THROTTLE_RATES.size - 1),
            valueRange = 0f..(EmulationViewModel.CPU_THROTTLE_RATES.size - 1).toFloat(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("1x", style = MaterialTheme.typography.labelSmall)
            Text("6x", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun mediaSelectionMatches(current: String?, value: String?): Boolean =
    (value == null && current == null) || (value != null && current == value)

private fun throttleLabel(rate: Double): String = when {
    rate == 1.0 -> "1x (off)"
    else -> "${rate.toInt()}x"
}

private fun dispatchMediaOverrides(
    viewModel: EmulationViewModel,
    media: String?,
    colorScheme: String?,
    reducedMotion: Boolean,
) {
    val features = mutableListOf<MediaFeatureModel>()
    colorScheme?.let { features.add(MediaFeatureModel(name = "prefers-color-scheme", value = it)) }
    if (reducedMotion) {
        features.add(MediaFeatureModel(name = "prefers-reduced-motion", value = "reduce"))
    }
    viewModel.setMediaOverrides(media, features)
}

@Composable
private fun ColorSchemeSelector(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Text("Prefers color scheme", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("No preference" to null, "Light" to "light", "Dark" to "dark").forEach { (label, value) ->
            OutlinedButton(
                onClick = { onSelect(if (selected == value) null else value) },
            ) {
                Text(label)
            }
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