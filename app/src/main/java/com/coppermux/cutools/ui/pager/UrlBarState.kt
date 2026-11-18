package com.coppermux.cutools.ui.pager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.data.model.WorkspaceSession

/**
 * Shared state backing the global URL bar across the pager screens. Loaded
 * from a session when navigating to the browser page, cleared on demand.
 */
class UrlBarState {
    var url by mutableStateOf("")
    var status by mutableStateOf(ConnectionStatus.UNKNOWN)
    var isHttps by mutableStateOf(false)

    fun loadSession(session: WorkspaceSession) {
        url = session.targetUrl
        status = session.status
        isHttps = session.targetUrl.startsWith("https")
    }

    fun clear() {
        url = ""
        status = ConnectionStatus.UNKNOWN
        isHttps = false
    }
}