package com.aharou.feature.agent.domain.execution

import android.content.ContextWrapper
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.agent.domain.tool.WorkspaceFileLock
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorktreeManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val executions = mockk<SessionExecutionRepository>(relaxed = true)
    private val engine = mockk<CommandEngine>()
    private val profiles = mockk<ContainerProfileResolver>()
    private val paths = mockk<WorkspacePathMapper>()
    private val commit = "a".repeat(40)
    private val parent = ExecutionBinding("parent", "/project", "/project", "builtin-alpine", "LOCAL_PROOT")

    private fun manager(): WorktreeManager {
        coEvery { executions.validate(any()) } answers { arg(0) }
        every { profiles.forExecution(any()) } returns ContainerProfile.BUILTIN_ALPINE
        every { paths.forExecution(any(), any()) } returns paths
        every { paths.resolveFile(any(), any(), any()) } answers { File(arg<String>(0)) }
        return WorktreeManager(object : ContextWrapper(null) { override fun getFilesDir() = temporary.root },
            executions, engine, profiles, paths, WorkspaceFileLock())
    }

    @Test
    fun preparedWorktreeUsesTheSelectedCommitAndStableMetadataMounts() = runTest {
        val manager = manager()
        val calls = mutableListOf<Pair<String, ExecutionBinding?>>()
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any(), any()) } answers {
            val command = arg<String>(0)
            calls += command to arg<ExecutionBinding?>(3)
            CommandResult(when {
                command.contains("--show-toplevel") -> "/project\n/project/.git\n$commit"
                command == "git status --porcelain" -> " M source.kt"
                command.contains("rev-parse HEAD") -> commit
                else -> ""
            }, 0)
        }
        val prepared = manager.prepare("child", parent)
        assertTrue(prepared.parentDirty)
        assertEquals(commit, prepared.binding.baseCommit)
        assertEquals("worktree", prepared.binding.isolation)
        assertTrue(prepared.binding.executionRoot.startsWith(temporary.root.canonicalPath))
        assertTrue(calls.any { (command, binding) -> command.contains("worktree add -b") && binding?.gitCommonDir == "/project/.git" && binding.mountRoot != null })
        assertFalse(calls.any { it.first.contains("stash") || it.first.contains("commit -") || it.first.contains("worktree remove") })
        coVerify(exactly = 1) { executions.ready("child", prepared.binding) }
    }

    @Test
    fun gitFailureMarksPreparationFailedAndDoesNotRunInTheParentAsFallback() = runTest {
        val manager = manager()
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any(), any()) } answers {
            val command = arg<String>(0)
            CommandResult(if (command.contains("--show-toplevel")) "/project\n/project/.git\n$commit" else "",
                if (command.contains("worktree add")) 1 else 0)
        }
        assertTrue(runCatching { manager.prepare("child", parent) }.exceptionOrNull() is java.io.IOException)
        coVerify(exactly = 1) { executions.failed("child", any()) }
        coVerify(exactly = 0) { executions.ready(any(), any()) }
    }

    @Test
    fun cancelledPreparationPersistsFailureAndPropagatesCancellation() = runTest {
        val manager = manager()
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any(), any()) } answers {
            val command = arg<String>(0)
            if (command.contains("worktree add")) throw CancellationException("cancelled")
            CommandResult(if (command.contains("--show-toplevel")) "/project\n/project/.git\n$commit" else "", 0)
        }
        assertTrue(runCatching { manager.prepare("child", parent) }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 1) { executions.failed("child", any()) }
    }

    @Test
    fun remotePreparationKeepsTheRealServerDirectory() = runTest {
        val manager = manager()
        every { profiles.forExecution(any()) } returns ContainerProfile.BUILTIN_ALPINE.copy(mode = com.aharou.feature.settings.data.repository.ExecutionMode.REMOTE_SSH)
        val remoteParent = parent.copy(executionRoot = "/srv/project", mode = "REMOTE_SSH", sshIdentity = "server")
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any(), any()) } answers {
            val command = arg<String>(0)
            CommandResult(if (command.contains("--show-toplevel")) "/srv/project\n/srv/project/.git\n$commit"
                else if (command.contains("rev-parse HEAD")) commit else "", 0)
        }
        val prepared = manager.prepare("child", remoteParent)
        assertTrue(prepared.binding.executionRoot.startsWith("/srv/project-aharou-worktrees/"))
        assertEquals("server", prepared.binding.sshIdentity)
        assertEquals(null, prepared.binding.mountRoot)
        io.mockk.verify(exactly = 0) { paths.forExecution(any(), any()) }
    }

    @Test
    fun mismatchedCheckoutCannotBecomeReady() = runTest {
        val manager = manager()
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any(), any()) } answers {
            val command = arg<String>(0)
            CommandResult(if (command.contains("--show-toplevel")) "/project\n/project/.git\n$commit"
                else if (command.contains("rev-parse HEAD")) "b".repeat(40) else "", 0)
        }
        assertTrue(runCatching { manager.prepare("child", parent) }.isFailure)
        coVerify(exactly = 0) { executions.ready(any(), any()) }
    }
}
