package com.coppermux.cutools.data.repository

import com.coppermux.cutools.data.local.WorkspaceSessionDao
import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.data.model.WorkspaceSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Repository for persisted workspace sessions. Encapsulates the Room DAO and
 * enforces capacity and classification semantics without automatic eviction.
 */
interface SessionRepository {
    companion object {
        const val MAX_CAPACITY = 9
    }

    suspend fun getAllSessions(): Flow<List<WorkspaceSession>>
    suspend fun getSessionById(id: String): WorkspaceSession?
    suspend fun saveSession(session: WorkspaceSession): SaveSessionResult
    suspend fun deleteSession(id: String)
    suspend fun renameSession(id: String, newName: String)
    suspend fun togglePin(id: String): Boolean
    suspend fun updateStatus(id: String, status: ConnectionStatus)
}

class SessionRepositoryImpl(
    private val dao: WorkspaceSessionDao
) : SessionRepository {

    private val saveMutex = Mutex()

    override suspend fun getAllSessions(): Flow<List<WorkspaceSession>> =
        flowOf(dao.getAllSessions())

    override suspend fun getSessionById(id: String): WorkspaceSession? =
        dao.getSessionById(id)

    override suspend fun saveSession(session: WorkspaceSession): SaveSessionResult {
        return saveMutex.withLock {
            dao.saveSessionAtomic(session, SessionRepository.MAX_CAPACITY)
        }
    }

    override suspend fun deleteSession(id: String) {
        dao.deleteSession(id)
    }

    override suspend fun renameSession(id: String, newName: String) {
        dao.updateDisplayName(id, newName)
    }

    override suspend fun togglePin(id: String): Boolean {
        val current = dao.getAllSessions().firstOrNull { it.id == id }
            ?: return false
        if (!current.isPinned) {
            val pinnedCount = dao.getAllSessions().count { it.isPinned }
            if (pinnedCount >= 3) {
                return false
            }
        }
        val newPinned = !current.isPinned
        dao.togglePin(id, newPinned)
        return newPinned
    }

    override suspend fun updateStatus(id: String, status: ConnectionStatus) {
        dao.updateStatus(id, status)
    }
}