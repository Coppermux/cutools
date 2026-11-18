package com.coppermux.cutools.ui.homescreen

import com.coppermux.cutools.data.model.WorkspaceSession
import com.coppermux.cutools.data.repository.SessionRepository
import com.coppermux.cutools.domain.SessionNameGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Retired — automatic workspace persistence is disabled.
 *
 * Manual Save Session via the hamburger menu is the sole entry point for
 * workspace creation and persistence. Automatic creation/persistence on
 * disconnect, background, and navigation is prohibited.
 */
class AutoSaveManager(
    private val sessionRepository: SessionRepository? = null,
    private val sessionNameGenerator: SessionNameGenerator? = null,
) {
    fun onDisconnect(session: WorkspaceSession) {
        // Automatic session persistence is disabled.
    }

    fun onBackground(session: WorkspaceSession) {
        // Automatic session persistence is disabled.
    }

    fun onNavigation(url: String, isIncognito: Boolean, isDesktopMode: Boolean) {
        // Automatic session persistence is disabled.
    }
}