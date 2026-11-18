package com.coppermux.cutools.data.repository

import com.coppermux.cutools.data.local.WorkspaceSessionDao
import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.data.model.WorkspaceCategory
import com.coppermux.cutools.data.model.WorkspaceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

class SessionRepositoryTest {

    private lateinit var dao: InMemoryWorkspaceSessionDao
    private lateinit var repository: SessionRepositoryImpl

    @Before
    fun setUp() {
        dao = InMemoryWorkspaceSessionDao()
        repository = SessionRepositoryImpl(dao)
    }

    private fun session(
        id: String,
        at: Long,
        pinned: Boolean = false,
        category: WorkspaceCategory = WorkspaceCategory.HOME,
        targetUrl: String = "https://$id.example"
    ): WorkspaceSession =
        WorkspaceSession(
            id = id,
            targetUrl = targetUrl,
            domain = "$id.example",
            displayName = "$id.example · 12:00",
            isIncognito = false,
            isDesktopMode = false,
            isPinned = pinned,
            lastActiveAt = at,
            createdAt = at,
            category = category
        )

    private suspend fun pinnedCount(): Int = dao.getAllSessions().count { it.isPinned }

    @Test
    fun saveSession_belowCapacity_createsNewSessionAndReturnsCreated() = runTest {
        val s1 = session("s1", 1L, category = WorkspaceCategory.WORK)
        val result = repository.saveSession(s1)

        assertTrue(result is SaveSessionResult.Created)
        assertEquals(s1, (result as SaveSessionResult.Created).session)

        val sessions = dao.getAllSessions()
        assertEquals(1, sessions.size)
        assertEquals("s1", sessions[0].id)
        assertEquals(WorkspaceCategory.WORK, sessions[0].category)
    }

    @Test
    fun saveSession_atCapacity_rejectsNewSessionAndReturnsCapacityReached() = runTest {
        for (i in 1..9) {
            val res = repository.saveSession(session("s$i", i.toLong()))
            assertTrue(res is SaveSessionResult.Created)
        }
        assertEquals(9, dao.getSessionCount())

        val rejected = repository.saveSession(session("s10", 10L))
        assertTrue(rejected is SaveSessionResult.CapacityReached)
        assertEquals(9, dao.getSessionCount())
        assertNull(dao.getSessionById("s10"))
    }

    @Test
    fun saveSession_atCapacity_preservesAllExistingRecordsWhenRejected() = runTest {
        for (i in 1..9) {
            repository.saveSession(session("s$i", i.toLong(), pinned = (i % 2 == 0)))
        }
        val beforeSessions = dao.getAllSessions()
        assertEquals(9, beforeSessions.size)

        val result = repository.saveSession(session("s10", 100L))
        assertTrue(result is SaveSessionResult.CapacityReached)

        val afterSessions = dao.getAllSessions()
        assertEquals(9, afterSessions.size)
        for (i in 1..9) {
            val orig = beforeSessions.first { it.id == "s$i" }
            val current = afterSessions.firstOrNull { it.id == "s$i" }
            assertNotNull(current)
            assertEquals(orig, current)
        }
    }

    @Test
    fun saveSession_atCapacity_updatesExistingSessionWithoutIncreasingCount() = runTest {
        for (i in 1..9) {
            repository.saveSession(session("s$i", i.toLong()))
        }
        assertEquals(9, dao.getSessionCount())

        val existing = session("s3", 999L, category = WorkspaceCategory.WORK)
        val result = repository.saveSession(existing)

        assertTrue(result is SaveSessionResult.Updated)
        assertEquals("s3", (result as SaveSessionResult.Updated).session.id)
        assertEquals(9, dao.getSessionCount())

        val updated = dao.getSessionById("s3")
        assertNotNull(updated)
        assertEquals(999L, updated!!.lastActiveAt)
        assertEquals(WorkspaceCategory.WORK, updated.category)
    }

    @Test
    fun saveSession_preservesExistingIdOnUpdate() = runTest {
        repository.saveSession(session("existing_id", 10L, targetUrl = "https://original.example"))
        val updatedSession = session("existing_id", 20L, targetUrl = "https://updated.example")

        val result = repository.saveSession(updatedSession)
        assertTrue(result is SaveSessionResult.Updated)

        val stored = dao.getSessionById("existing_id")
        assertNotNull(stored)
        assertEquals("existing_id", stored!!.id)
        assertEquals("https://updated.example", stored.targetUrl)
        assertEquals(20L, stored.lastActiveAt)
    }

    @Test
    fun saveSession_doesNotOverwriteDifferentSessionWithSameUrl() = runTest {
        val s1 = session("s1", 1L, targetUrl = "https://shared.example")
        val s2 = session("s2", 2L, targetUrl = "https://shared.example")

        repository.saveSession(s1)
        repository.saveSession(s2)

        val sessions = dao.getAllSessions()
        assertEquals(2, sessions.size)
        assertTrue(sessions.any { it.id == "s1" && it.targetUrl == "https://shared.example" })
        assertTrue(sessions.any { it.id == "s2" && it.targetUrl == "https://shared.example" })
    }

    @Test
    fun deleteSession_freesSlot_allowsNewSessionCreation() = runTest {
        for (i in 1..9) {
            repository.saveSession(session("s$i", i.toLong()))
        }
        assertEquals(9, dao.getSessionCount())

        // Rejection when full
        val rejectResult = repository.saveSession(session("s10", 10L))
        assertTrue(rejectResult is SaveSessionResult.CapacityReached)

        // Delete one session
        repository.deleteSession("s5")
        assertEquals(8, dao.getSessionCount())
        assertNull(dao.getSessionById("s5"))

        // Now save succeeds
        val successResult = repository.saveSession(session("s10", 10L))
        assertTrue(successResult is SaveSessionResult.Created)
        assertEquals(9, dao.getSessionCount())
        assertNotNull(dao.getSessionById("s10"))
    }

    @Test
    fun saveSession_persistsWorkAndHomeCategories() = runTest {
        val workSession = session("work1", 1L, category = WorkspaceCategory.WORK)
        val homeSession = session("home1", 2L, category = WorkspaceCategory.HOME)

        repository.saveSession(workSession)
        repository.saveSession(homeSession)

        val storedWork = dao.getSessionById("work1")
        val storedHome = dao.getSessionById("home1")

        assertNotNull(storedWork)
        assertEquals(WorkspaceCategory.WORK, storedWork!!.category)
        assertNotNull(storedHome)
        assertEquals(WorkspaceCategory.HOME, storedHome!!.category)
    }

    @Test
    fun saveSession_noAutomaticLruDeletion() = runTest {
        // Save sessions with oldest timestamps first
        for (i in 1..9) {
            repository.saveSession(session("s$i", i.toLong(), pinned = false))
        }

        // Attempt to save 10th session
        val result = repository.saveSession(session("s10", 10L))
        assertTrue(result is SaveSessionResult.CapacityReached)

        // Oldest session (s1) must NOT be pruned or deleted
        val s1 = dao.getSessionById("s1")
        assertNotNull("Oldest session s1 must not be deleted by LRU eviction", s1)
        assertEquals(9, dao.getSessionCount())
    }

    @Test
    fun saveSession_concurrentSaves_doNotExceedCapacity() = runTest {
        // Prepopulate with 8 sessions (1 slot remaining)
        for (i in 1..8) {
            val res = repository.saveSession(session("s$i", i.toLong()))
            assertTrue(res is SaveSessionResult.Created)
        }
        assertEquals(8, dao.getSessionCount())

        // Concurrently attempt to save 10 new sessions
        val results = Collections.synchronizedList(mutableListOf<SaveSessionResult>())
        coroutineScope {
            for (i in 9..18) {
                launch(Dispatchers.Default) {
                    val res = repository.saveSession(session("s$i", i.toLong()))
                    results.add(res)
                }
            }
        }

        val createdCount = results.count { it is SaveSessionResult.Created }
        val capacityReachedCount = results.count { it is SaveSessionResult.CapacityReached }

        assertEquals(1, createdCount)
        assertEquals(9, capacityReachedCount)
        assertEquals(9, dao.getSessionCount())
    }

    @Test
    fun togglePin_returnsFalse_whenMaxPinnedReached() = runTest {
        repository.saveSession(session("s1", 1L))
        repository.saveSession(session("s2", 2L))
        repository.saveSession(session("s3", 3L))
        repository.saveSession(session("s4", 4L))
        assertTrue(repository.togglePin("s1"))
        assertTrue(repository.togglePin("s2"))
        assertTrue(repository.togglePin("s3"))
        assertFalse(repository.togglePin("s4"))
        assertEquals(3, pinnedCount())
    }

    @Test
    fun togglePin_unpinsSuccessfully() = runTest {
        repository.saveSession(session("s1", 1L))
        assertTrue(repository.togglePin("s1"))
        assertEquals(1, pinnedCount())
        assertFalse(repository.togglePin("s1"))
        assertEquals(0, pinnedCount())
    }

    @Test
    fun renameSession_updatesDisplayName() = runTest {
        repository.saveSession(session("s1", 1L))
        repository.renameSession("s1", "Renamed Session")
        val updated = dao.getAllSessions().first()
        assertEquals("Renamed Session", updated.displayName)
    }

    @Test
    fun getAllSessions_ordersByWorkThenHomeAndLastActive() = runTest {
        repository.saveSession(session("home_old", 1L, category = WorkspaceCategory.HOME))
        repository.saveSession(session("home_new", 10L, category = WorkspaceCategory.HOME))
        repository.saveSession(session("work_old", 2L, category = WorkspaceCategory.WORK))
        repository.saveSession(session("work_new", 20L, category = WorkspaceCategory.WORK))
        val sessions = dao.getAllSessions()
        assertEquals("work_new", sessions[0].id)
        assertEquals("work_old", sessions[1].id)
        assertEquals("home_new", sessions[2].id)
        assertEquals("home_old", sessions[3].id)
    }
}

private class InMemoryWorkspaceSessionDao : WorkspaceSessionDao() {

    private val sessions = mutableListOf<WorkspaceSession>()
    private val mutex = Mutex()

    override suspend fun getAllSessions(): List<WorkspaceSession> = mutex.withLock {
        sessions.sortedWith(
            compareBy<WorkspaceSession> { if (it.category == WorkspaceCategory.WORK) 0 else 1 }
                .thenByDescending { it.lastActiveAt }
        )
    }

    override suspend fun getSessionById(id: String): WorkspaceSession? = mutex.withLock {
        sessions.firstOrNull { it.id == id }
    }

    override suspend fun insertSession(session: WorkspaceSession): Unit = mutex.withLock {
        sessions.add(session)
    }

    override suspend fun updateSession(session: WorkspaceSession): Unit = mutex.withLock {
        val i = sessions.indexOfFirst { it.id == session.id }
        if (i != -1) sessions[i] = session
    }

    override suspend fun upsert(session: WorkspaceSession): Unit = mutex.withLock {
        val i = sessions.indexOfFirst { it.id == session.id }
        if (i != -1) sessions[i] = session else sessions.add(session)
    }

    override suspend fun deleteSession(id: String): Unit = mutex.withLock {
        sessions.removeAll { it.id == id }
    }

    override suspend fun getSessionCount(): Int = mutex.withLock {
        sessions.size
    }

    override suspend fun updateStatus(id: String, status: ConnectionStatus): Unit = mutex.withLock {
        val i = sessions.indexOfFirst { it.id == id }
        if (i != -1) sessions[i] = sessions[i].copy(status = status)
    }

    override suspend fun updateDisplayName(id: String, name: String): Unit = mutex.withLock {
        val i = sessions.indexOfFirst { it.id == id }
        if (i != -1) sessions[i] = sessions[i].copy(displayName = name)
    }

    override suspend fun togglePin(id: String, pinned: Boolean): Unit = mutex.withLock {
        val i = sessions.indexOfFirst { it.id == id }
        if (i != -1) sessions[i] = sessions[i].copy(isPinned = pinned)
    }
}