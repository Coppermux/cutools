package com.coppermux.cutools.ui.homescreen

import androidx.lifecycle.ViewModel
import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.data.model.WorkspaceCategory
import com.coppermux.cutools.data.model.WorkspaceSession
import com.coppermux.cutools.data.repository.SessionRepository
import com.coppermux.cutools.domain.PingService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Backs the Workspaces screen: session list, ping/refresh, and CRUD actions. */
@HiltViewModel
class WorkspacesViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val pingService: PingService,
) : ViewModel() {
    private val _sessions = MutableStateFlow<List<WorkspaceSession>>(emptyList())
    val sessions: StateFlow<List<WorkspaceSession>> = _sessions

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    suspend fun load() {
        _sessions.value = sortSessions(sessionRepository.getAllSessions().first())
    }

    suspend fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        try {
            val current = _sessions.value
            val pings = pingService.pingAll(current)
            current.forEach { session ->
                pings[session.id]?.let { status ->
                    sessionRepository.updateStatus(session.id, status)
                }
            }
            _sessions.value = sortSessions(sessionRepository.getAllSessions().first())
        } finally {
            _isRefreshing.value = false
        }
    }

    suspend fun onTileClick(session: WorkspaceSession, onNavigateToBrowser: (WorkspaceSession) -> Unit) {
        sessionRepository.saveSession(session.copy(lastActiveAt = System.currentTimeMillis()))
        onNavigateToBrowser(session)
    }

    suspend fun onRename(sessionId: String, newName: String) {
        sessionRepository.renameSession(sessionId, newName)
        load()
    }

    suspend fun onDelete(sessionId: String) {
        sessionRepository.deleteSession(sessionId)
        load()
    }

    private fun sortSessions(list: List<WorkspaceSession>): List<WorkspaceSession> {
        return list.sortedWith(
            compareBy<WorkspaceSession> { if (it.category == WorkspaceCategory.WORK) 0 else 1 }
                .thenByDescending { it.lastActiveAt }
        )
    }
}