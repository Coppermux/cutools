package com.coppermux.cutools.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.data.model.WorkspaceSession
import com.coppermux.cutools.data.repository.SaveSessionResult

@Dao
abstract class WorkspaceSessionDao {

    @Query("SELECT * FROM workspace_sessions ORDER BY CASE WHEN category = 'WORK' THEN 0 ELSE 1 END, lastActiveAt DESC")
    abstract suspend fun getAllSessions(): List<WorkspaceSession>

    @Query("SELECT * FROM workspace_sessions WHERE id = :id LIMIT 1")
    abstract suspend fun getSessionById(id: String): WorkspaceSession?

    @Insert
    abstract suspend fun insertSession(session: WorkspaceSession)

    @Update
    abstract suspend fun updateSession(session: WorkspaceSession)

    @Upsert
    abstract suspend fun upsert(session: WorkspaceSession)

    @Query("DELETE FROM workspace_sessions WHERE id = :id")
    abstract suspend fun deleteSession(id: String)

    @Query("SELECT COUNT(*) FROM workspace_sessions")
    abstract suspend fun getSessionCount(): Int

    @Query("UPDATE workspace_sessions SET status = :status WHERE id = :id")
    abstract suspend fun updateStatus(id: String, status: ConnectionStatus)

    @Query("UPDATE workspace_sessions SET displayName = :name WHERE id = :id")
    abstract suspend fun updateDisplayName(id: String, name: String)

    @Query("UPDATE workspace_sessions SET isPinned = :pinned WHERE id = :id")
    abstract suspend fun togglePin(id: String, pinned: Boolean)

    @Transaction
    open suspend fun saveSessionAtomic(session: WorkspaceSession, maxCapacity: Int): SaveSessionResult {
        val existing = getSessionById(session.id)
        return if (existing != null) {
            updateSession(session)
            SaveSessionResult.Updated(session)
        } else {
            val count = getSessionCount()
            if (count >= maxCapacity) {
                SaveSessionResult.CapacityReached
            } else {
                insertSession(session)
                SaveSessionResult.Created(session)
            }
        }
    }
}