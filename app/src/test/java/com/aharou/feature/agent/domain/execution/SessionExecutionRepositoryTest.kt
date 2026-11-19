package com.aharou.feature.agent.domain.execution

import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.dao.SessionExecutionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.local.entity.SessionExecutionEntity
import com.aharou.feature.agent.domain.container.ActiveRemoteConnectionResolver
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.agent.domain.subagent.SubAgentWriteLease
import com.aharou.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExecutionRepositoryTest {
    private val dao = FakeDao()
    private val sessions = mockk<ChatSessionDao>()
    private val profiles = mockk<ContainerProfileResolver>()
    private val connections = mockk<ActiveRemoteConnectionResolver>()
    private val remote = mockk<RemoteSshConnection>(relaxed = true)
    private val files = mockk<FileAccessProvider>(relaxed = true)
    private val leases = SubAgentWriteLease()
    private val repository = SessionExecutionRepository(dao, sessions, profiles, connections, remote,
        dagger.Lazy { files }, leases)
    private val binding = ExecutionBinding("execution", "/project", "/project", "builtin-alpine", "LOCAL_PROOT")

    init {
        coEvery { sessions.getById(any()) } answers { ChatSessionEntity(arg(0), "task", 1, 1, workspacePath = "/project") }
        coEvery { profiles.workspaceProfile(any()) } returns ContainerProfile.BUILTIN_ALPINE
        coEvery { profiles.bindExecution(any()) } returns ContainerProfile.BUILTIN_ALPINE
        every { files.forExecution(any(), any()) } returns files
        every { files.forStrictRead() } returns files
        every { files.isDirectory(any()) } returns true
        every { files.resourceKey(any()) } answers { "local\u0000${arg<String>(0)}" }
    }

    @Test fun firstResolutionPersistsSharedAndIgnoresAnUnrelatedSelectedRoot() = runTest {
        val first = repository.resolve("session", "/other")
        assertEquals("/project", first.projectRoot)
        assertEquals("shared", first.isolation)
        assertEquals(first, repository.resolve("session", "/another"))
        assertEquals(first.id, dao.rows.getValue("session").executionId)
    }

    @Test fun reopeningUsesTheOriginalBindingAndRestoresReadOnlyLease() = runTest {
        dao.rows["session"] = SessionExecutionEntity.from("session", binding).copy(writePaths = "[]")
        assertEquals(binding, repository.resolve("session"))
        assertEquals(emptySet<String>(), leases.pathsFor("session"))
        coVerify(exactly = 0) { profiles.workspaceProfile(any()) }
    }

    @Test fun aNewTaskWithMissingPreparationNeverFallsBackToTheProjectDirectory() = runTest {
        coEvery { sessions.getById("session") } returns ChatSessionEntity("session", "task", 1, 1,
            workspacePath = "/project", executionBindingRequired = true)
        assertTrue(runCatching { repository.resolve("session") }.exceptionOrNull() is java.io.IOException)
        assertTrue(dao.rows.isEmpty())
        coVerify(exactly = 0) { profiles.workspaceProfile(any()) }
    }

    @Test fun preparingWorkspaceCannotStart() = runTest {
        dao.rows["session"] = SessionExecutionEntity.from("session", binding, SessionExecutionEntity.PREPARING)
        val result = runCatching { repository.resolve("session") }
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    @Test fun replacingAnEstablishedBindingIsRejected() = runTest {
        repository.attach("session", binding)
        assertTrue(runCatching { repository.attach("session", binding.copy(id = "other", executionRoot = "/other")) }.isFailure)
        assertEquals(binding, dao.rows.getValue("session").binding())
    }

    @Test fun sourceProjectRenameDoesNotRecreateTheOldDirectory() = runTest {
        dao.rows["session"] = SessionExecutionEntity.from("session", binding)
        coEvery { sessions.getById("session") } returns ChatSessionEntity("session", "task", 1, 1, workspacePath = "/renamed")
        assertTrue(runCatching { repository.resolve("session") }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun removedWorkspaceIsNotTreatedAsSharedFallback() = runTest {
        dao.rows["session"] = SessionExecutionEntity.from("session", binding)
        every { files.isDirectory(any()) } returns false
        assertTrue(runCatching { repository.resolve("session") }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun writePathsSurviveRepositoryRecreationAndForgetDoesNotDeleteFiles() = runTest {
        repository.attach("session", binding)
        repository.saveWritePaths("session", setOf("src"))
        val recreated = SessionExecutionRepository(dao, sessions, profiles, connections, remote, dagger.Lazy { files }, leases)
        recreated.resolve("session")
        assertEquals(setOf("src"), leases.pathsFor("session"))
        recreated.forget("session")
        assertTrue(dao.rows.isEmpty())
        coVerify(exactly = 0) { files.delete(any()) }
    }

    @Test fun changedSshIdentityIsRejectedBeforeConnecting() = runTest {
        val profile = ContainerProfile.BUILTIN_ALPINE.copy(mode = com.aharou.feature.settings.data.repository.ExecutionMode.REMOTE_SSH)
        coEvery { profiles.bindExecution(any()) } returns profile
        val config = com.aharou.feature.agent.domain.container.RemoteConnectionConfig("example.invalid", 22, "user",
            com.aharou.feature.workspace.domain.remote.RemoteAuth.Password("fixture"), "/srv/projects")
        coEvery { connections.configForProfile(any()) } returns config
        assertTrue(runCatching { repository.validate(binding.copy(mode = "REMOTE_SSH", sshIdentity = "another-server")) }.isFailure)
        coVerify(exactly = 0) { remote.connect(any()) }
    }

    @Test fun worktreeMetadataCannotBeReplacedWithAnotherRepository() = runTest {
        val worktree = binding.copy(executionRoot = "/worktrees/task", isolation = "worktree", gitCommonDir = "/project/.git", branch = "aharou/task")
        every { files.readFile("/worktrees/task/.git") } returns "gitdir: /other/.git/worktrees/task"
        assertTrue(runCatching { repository.validate(worktree) }.exceptionOrNull() is java.io.IOException)
    }

    private class FakeDao : SessionExecutionDao {
        val rows = mutableMapOf<String, SessionExecutionEntity>()
        override suspend fun get(sessionId: String) = rows[sessionId]
        override fun observe(sessionId: String) = flowOf(rows[sessionId])
        override suspend fun insert(entity: SessionExecutionEntity): Long {
            if (rows.containsKey(entity.sessionId)) return -1
            rows[entity.sessionId] = entity
            return rows.size.toLong()
        }
        override suspend fun update(entity: SessionExecutionEntity): Int {
            if (!rows.containsKey(entity.sessionId)) return 0
            rows[entity.sessionId] = entity
            return 1
        }
        override suspend fun updateState(sessionId: String, executionId: String, state: String): Int {
            val row = rows[sessionId]?.takeIf { it.executionId == executionId } ?: return 0
            rows[sessionId] = row.copy(state = state)
            return 1
        }
        override suspend fun getExecution(id: String) = rows.values.firstOrNull { it.executionId == id && it.state == "ready" }
        override suspend fun updateWritePaths(sessionId: String, paths: String): Int {
            val row = rows[sessionId] ?: return 0
            rows[sessionId] = row.copy(writePaths = paths)
            return 1
        }
        override suspend fun delete(sessionId: String) { rows.remove(sessionId) }
    }
}
