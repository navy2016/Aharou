package com.aharou.feature.agent.domain.execution

import android.content.ContextWrapper
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.dao.SessionExecutionDao
import com.aharou.feature.agent.data.local.entity.SessionExecutionEntity
import com.aharou.feature.agent.domain.container.ActiveRemoteConnectionResolver
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.agent.domain.subagent.SubAgentWriteLease
import com.aharou.feature.agent.domain.tool.WorkspaceFileLock
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.LocalFileAccess
import com.aharou.feature.workspace.domain.PathHomeResolver
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorktreeFileIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var project: File
    private lateinit var parent: ExecutionBinding
    private lateinit var local: LocalFileAccess
    private lateinit var repository: SessionExecutionRepository
    private lateinit var manager: WorktreeManager
    private val profile = ContainerProfile.BUILTIN_ALPINE
    private val rows = mutableMapOf<String, SessionExecutionEntity>()

    private fun command(command: String, directory: File): CommandResult {
        val process = ProcessBuilder("sh", "-c", command).directory(directory).redirectErrorStream(true).start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Git fixture command timed out")
        }
        return CommandResult(process.inputStream.bufferedReader().readText(), process.exitValue())
    }

    @Before fun prepare() {
        project = temporary.newFolder("project")
        File(project, "state.txt").writeText("BASE\n")
        assertEquals(0, command("git init -b acceptance && git add state.txt && git -c user.name=Acceptance -c user.email=acceptance@example.invalid -c commit.gpgsign=false commit -m baseline", project).exitCode)
        val installer = mockk<ContainerInstaller>()
        every { installer.rootfsDirFor(any()) } returns temporary.newFolder("rootfs")
        every { installer.aharouDir } returns temporary.newFolder("aharou")
        every { installer.sharedDir } returns temporary.newFolder("shared")
        val workspaces = mockk<WorkspaceRepository>()
        every { workspaces.currentPath() } returns project.path
        val settings = mockk<ContainerSettingsRepository>(relaxed = true)
        every { settings.activeProfileIdFlow } returns emptyFlow()
        val mapper = WorkspacePathMapper(workspaces, installer, settings, mockk<PathHomeResolver>())
        local = LocalFileAccess(mapper)
        parent = ExecutionBinding("parent", project.path, project.path, profile.id, profile.mode.name)
        val profiles = mockk<ContainerProfileResolver>()
        coEvery { profiles.bindExecution(any()) } returns profile
        every { profiles.forExecution(any()) } returns profile
        val dao = mockk<SessionExecutionDao>()
        coEvery { dao.get(any()) } coAnswers { rows[firstArg<String>()] }
        coEvery { dao.insert(any()) } coAnswers {
            val row = firstArg<SessionExecutionEntity>()
            rows[row.sessionId] = row
            rows.size.toLong()
        }
        coEvery { dao.update(any()) } coAnswers {
            val row = firstArg<SessionExecutionEntity>()
            rows[row.sessionId] = row
            1
        }
        coEvery { dao.updateState(any(), any(), any()) } coAnswers {
            val id = firstArg<String>()
            val row = rows[id]
            if (row == null || row.executionId != secondArg<String>()) 0
            else { rows[id] = row.copy(state = thirdArg<String>()); 1 }
        }
        val files = mockk<FileAccessProvider>()
        every { files.forExecution(any(), any()) } answers { local.forExecution(firstArg(), profile) }
        repository = SessionExecutionRepository(dao, mockk<ChatSessionDao>(), profiles,
            mockk<ActiveRemoteConnectionResolver>(), mockk<RemoteSshConnection>(), dagger.Lazy { files }, SubAgentWriteLease())
        val engine = mockk<CommandEngine>()
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any(), any()) } coAnswers {
            val script = firstArg<String>()
            val result = command(script, File(arg<ExecutionBinding>(3).executionRoot))
            if (script.contains("--show-toplevel") && result.exitCode == 0) {
                result.copy(output = result.output.replace(project.canonicalPath, "/root/workspace"))
            } else result
        }
        manager = WorktreeManager(object : ContextWrapper(null) { override fun getFilesDir() = temporary.root },
            repository, engine, profiles, mapper, WorkspaceFileLock())
    }

    @Test fun realGitWorktreePassesTheActualFileAndMetadataValidation() = runTest {
        File(project, "state.txt").writeText("PARENT_DIRTY\n")
        val prepared = manager.prepare("child", parent)
        val binding = prepared.binding
        assertEquals(SessionExecutionEntity.READY, rows.getValue("child").state)
        assertTrue(prepared.parentDirty)
        assertEquals(binding, repository.validate(binding))
        val access = local.forExecution(binding, profile)
        assertEquals("BASE\n", access.readFile("~/workspace/state.txt"))
        assertEquals("BASE\n", access.readFile(binding.executionRoot + "/state.txt"))
        access.writeFile("~/workspace/state.txt", "WORKTREE\n")
        assertEquals("PARENT_DIRTY\n", File(project, "state.txt").readText())
        assertEquals("WORKTREE\n", command("cat state.txt", File(binding.executionRoot)).output)
        assertTrue(File(binding.executionRoot, ".git").isFile)
    }

    @Test fun independentTreesMayUseTheSameRelativeFileName() = runTest {
        val first = manager.prepare("first", parent).binding
        val second = manager.prepare("second", parent).binding
        val a = local.forExecution(first, profile)
        val b = local.forExecution(second, profile)
        a.writeFile("~/workspace/same.txt", "A")
        b.writeFile("~/workspace/same.txt", "B")
        assertFalse(a.resourceKey("~/workspace/same.txt") == b.resourceKey("~/workspace/same.txt"))
        assertEquals("A", a.readFile("~/workspace/same.txt"))
        assertEquals("B", b.readFile("~/workspace/same.txt"))
        assertFalse(File(project, "same.txt").exists())
    }

    @Test fun aChangedBranchIsRejectedByRealMetadataReads() = runTest {
        val binding = manager.prepare("child", parent).binding
        assertEquals(0, command("git switch -c changed-branch", File(binding.executionRoot)).exitCode)
        val failure = runCatching { repository.validate(binding) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertTrue(failure?.message.orEmpty().contains("branch mismatch"))
    }

    @Test fun missingPointerIsNotTreatedAsTheParentWorkspace() = runTest {
        val binding = manager.prepare("child", parent).binding
        assertTrue(File(binding.executionRoot, ".git").renameTo(File(binding.executionRoot, ".git.saved")))
        assertTrue(runCatching { repository.validate(binding) }.isFailure)
        assertEquals("BASE\n", File(project, "state.txt").readText())
    }
}
