package com.coppermux.cutools.ui.security

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.ui.security.components.CertificateDetailsScreen

/**
 * Phase 13 — Security inspection tool host.
 *
 * Mounts the [CertificateDetailsScreen] Compose view backed by the shared
 * [SecurityViewModel] from [CuToolsApp]. The activity is a pure renderer: it
 * never talks to raw CDP and never owns browser truth — all state and actions
 * flow through the ViewModel to the security engine.
 */
class SecurityActivity : AppCompatActivity() {

    private val viewModel get() = (application as CuToolsApp).securityViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this).apply {
            setContent { CertificateDetailsScreen(viewModel = viewModel) }
        }
        setContentView(composeView)
    }
}