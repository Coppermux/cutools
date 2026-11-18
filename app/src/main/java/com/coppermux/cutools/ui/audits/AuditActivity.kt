package com.coppermux.cutools.ui.audits

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import com.coppermux.cutools.CuToolsApp
import com.coppermux.cutools.ui.audits.components.AuditIssuesScreen

/**
 * Phase 11B — Audits tool host.
 *
 * Mounts the [AuditIssuesScreen] Compose view backed by the shared
 * [AuditViewModel] from [CuToolsApp]. The activity is a pure renderer: it never
 * talks to raw CDP and never owns browser truth — all state and actions flow
 * through the ViewModel to the audit engine.
 */
class AuditActivity : AppCompatActivity() {

    private val viewModel get() = (application as CuToolsApp).auditViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this).apply {
            setContent { AuditIssuesScreen(viewModel = viewModel) }
        }
        setContentView(composeView)
    }
}