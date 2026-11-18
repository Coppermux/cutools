package com.coppermux.cutools.ui.emulation

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.ui.emulation.components.DeviceMetricsScreen
import com.coppermux.cutools.ui.emulation.components.NetworkEmulationScreen

/**
 * Phase 9C — Emulation tool host.
 *
 * Mounts the [DeviceMetricsScreen] and [NetworkEmulationScreen] Compose views
 * backed by the shared [EmulationViewModel] from [CuToolsApp], with a simple
 * toggle between the two panels. The activity is a pure renderer — all state
 * and actions flow through the ViewModel to the emulation engine.
 */
class EmulationActivity : AppCompatActivity() {

    private val viewModel get() = (application as CuToolsApp).emulationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this).apply {
            setContent {
                EmulationTabs(viewModel = viewModel)
            }
        }
        setContentView(composeView)
    }
}

@Composable
private fun EmulationTabs(viewModel: EmulationViewModel) {
    var showNetwork by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                text = "Emulation",
                style = MaterialTheme.typography.titleLarge,
            )
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showNetwork = false }) {
                    Text("Device Metrics")
                }
                OutlinedButton(onClick = { showNetwork = true }) {
                    Text("Network / Touch / Media")
                }
            }
        }
        if (showNetwork) {
            NetworkEmulationScreen(viewModel = viewModel)
        } else {
            DeviceMetricsScreen(viewModel = viewModel)
        }
    }
}