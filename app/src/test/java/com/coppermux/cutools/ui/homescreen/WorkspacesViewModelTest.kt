package com.coppermux.cutools.ui.homescreen

import com.coppermux.cutools.data.local.WorkspaceSessionDao
import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.data.model.WorkspaceCategory
import com.coppermux.cutools.data.model.WorkspaceSession
import com.coppermux.cutools.data.repository.SessionRepository
import com.coppermux.cutools.data.repository.SessionRepositoryImpl
import com.coppermux.cutools.domain.PingService
import com.coppermux.cutools.domain.SessionNameGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspacesViewModelTest {

    private lateinit var dao: FakeWorkspaceSessionDao
    private lateinit var repository: SessionRepository
    private lateinit var pingService: PingService
    private lateinit var viewModel: WorkspacesViewModel

    @Before
    fun setUp() {
        dao = FakeWorkspaceSessionDao()
        repository = SessionRepositoryImpl(dao)
        pingService = PingService(OkHttpClient())
        viewModel = WorkspacesViewModel(repository, pingService)
    }

    private fun session(
        id: String,
        lastActiveAt: Long,
        category: WorkspaceCategory = WorkspaceCategory.HOME,
    ): WorkspaceSession = WorkspaceSession(
        id = id,
        targetUrl = "https://$id.example",
        domain = "$id.example",
        displayName = "Session $id",
        lastActiveAt = lastActiveAt,
        category = category,
    )

    @Test
    fun load_ordersSessionsWorkBeforeHome_andDescendingLastActive() = runTest {
        repository.saveSession(session("home_old", 100L, WorkspaceCategory.HOME))
        repository.saveSession(session("home_new", 300L, WorkspaceCategory.HOME))
        repository.saveSession(session("work_old", 200L, WorkspaceCategory.WORK))
        repository.saveSession(session("work_new", 400L, WorkspaceCategory.WORK))

        viewModel.load()

        val sessions = viewModel.sessions.value
        assertEquals(4, sessions.size)
        assertEquals("work_new", sessions[0].id)
        assertEquals("work_old", sessions[1].id)
        assertEquals("home_new", sessions[2].id)
        assertEquals("home_old", sessions[3].id)
    }

    @Test
    fun onTileClick_updatesLastActiveAt_andCallsNavigationCallback() = runTest {
        val original = session("work1", 100L, WorkspaceCategory.WORK)
        repository.saveSession(original)
        viewModel.load()

        var navigated = false
        var navigatedSession: WorkspaceSession? = null
        viewModel.onTileClick(original) { session ->
            navigated = true
            navigatedSession = session
        }

        assertTrue(navigated)
        assertEquals("work1", navigatedSession?.id)
        val updated = repository.getSessionById("work1")
        assertTrue(updated!!.lastActiveAt > 100L)
    }

    @Test
    fun onRename_updatesSessionDisplayName_andReloads() = runTest {
        repository.saveSession(session("s1", 100L, WorkspaceCategory.WORK))
        viewModel.load()

        viewModel.onRename("s1", "Updated Name")

        assertEquals("Updated Name", viewModel.sessions.value.first { it.id == "s1" }.displayName)
    }

    @Test
    fun onDelete_removesSession_andReloads() = runTest {
        repository.saveSession(session("s1", 100L, WorkspaceCategory.WORK))
        repository.saveSession(session("s2", 200L, WorkspaceCategory.HOME))
        viewModel.load()
        assertEquals(2, viewModel.sessions.value.size)

        viewModel.onDelete("s1")

        assertEquals(1, viewModel.sessions.value.size)
        assertEquals("s2", viewModel.sessions.value[0].id)
    }

    @Test
    fun autoSaveManager_methodsAreNoOpAndDoNotPersist() = runTest {
        val autoSave = AutoSaveManager(repository, SessionNameGenerator())
        autoSave.onNavigation("https://example.com", isIncognito = false, isDesktopMode = false)
        autoSave.onDisconnect(session("s1", 100L))
        autoSave.onBackground(session("s2", 200L))

        val sessions = repository.getAllSessions().first()
        assertTrue(sessions.isEmpty())
    }
}

private class FakeWorkspaceSessionDao : WorkspaceSessionDao() {
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
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx != -1) sessions[idx] = session
    }

    override suspend fun upsert(session: WorkspaceSession): Unit = mutex.withLock {
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx != -1) sessions[idx] = session else sessions.add(session)
    }

    override suspend fun deleteSession(id: String): Unit = mutex.withLock {
        sessions.removeAll { it.id == id }
    }

    override suspend fun getSessionCount(): Int = mutex.withLock {
        sessions.size
    }

    override suspend fun updateStatus(id: String, status: ConnectionStatus): Unit = mutex.withLock {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx != -1) sessions[idx] = sessions[idx].copy(status = status)
    }

    override suspend fun updateDisplayName(id: String, name: String): Unit = mutex.withLock {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx != -1) sessions[idx] = sessions[idx].copy(displayName = name)
    }

    override suspend fun togglePin(id: String, pinned: Boolean): Unit = mutex.withLock {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx != -1) sessions[idx] = sessions[idx].copy(isPinned = pinned)
    }
}
