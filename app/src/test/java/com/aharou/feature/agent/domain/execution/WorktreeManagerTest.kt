package com.aharou.feature.agent.domain.execution

import android.content.ContextWrapper
import com.aharou.feature.agent.data.local.entity.SessionExecutionEntity
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

    private fun gitMetadata(command: String, execution: ExecutionBinding, head: String = commit): String = when {
        command.contains("git symbolic-ref HEAD") ->
            "${execution.executionRoot}\n${execution.gitCommonDir}\n$head\nrefs/heads/${execution.branch}"
        command.contains("--show-toplevel") -> "${execution.executionRoot}\n${execution.executionRoot}/.git\n$commit"
        else -> ""
    }

    @Test
    fun preparedWorktreeUsesTheSelectedCommitAndStableMetadataMounts() = runTest {
        val manager = manager()
        val calls = mutableListOf<Pair<String, ExecutionBinding>>()
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any(), any()) } answers {
            val command = arg<String>(0)
            val execution = arg<ExecutionBinding>(3)
            calls += command to execution
            CommandResult(if (command == "git status --porcelain") " M source.kt" else gitMetadata(command, execution), 0)
        }
        val prepared = manager.prepare("child", parent)
        assertTrue(prepared.parentDirty)
        assertEquals(commit, prepared.binding.baseCommit)
        assertEquals("worktree", prepared.binding.isolation)
        assertTrue(prepared.binding.executionRoot.startsWith(temporary.root.canonicalPath))
        val creation = calls.single { it.first.contains("worktree add -b") }.second
        assertEquals("/project", creation.executionRoot)
        assertEquals("/project/.git", creation.gitCommonDir)
        assertEquals(SessionExecutionEntity.PREPARING, creation.preparationState)
        assertFalse(creation.ready)
        assertEquals(prepared.binding.mountRoot, creation.mountRoot)
        assertEquals(prepared.binding, calls.single { it.first.contains("git symbolic-ref HEAD") }.second)
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
            CommandResult(gitMetadata(command, arg(3)), 0)
        }
        val prepared = manager.prepare("child", remoteParent)
        assertTrue(prepared.binding.executionRoot.startsWith("/srv/project-aharou-worktrees/"))
        assertEquals("server", prepared.binding.sshIdentity)
        assertEquals(null, prepared.binding.mountRoot)
        io.mockk.verify(exactly = 0) { paths.forExecution(any(), any()) }
    }

    @Test
    fun validationFailureAfterSuccessfulGitReportsTheStageAndCause() = runTest {
        val manager = manager()
        val cause = java.io.IOException("Execution worktree branch mismatch")
        coEvery { executions.validate(match { it.isWorktree }) } throws cause
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any(), any()) } answers {
            val command = arg<String>(0)
            CommandResult(gitMetadata(command, arg(3)), 0)
        }
        val failure = runCatching { manager.prepare("child", parent) }.exceptionOrNull()
        assertTrue(failure is WorktreeManager.PreparationException)
        assertEquals("validate_execution", (failure as WorktreeManager.PreparationException).stage)
        assertTrue(failure.cause === cause)
        assertTrue(failure.message.orEmpty().contains(cause.message.orEmpty()))
        coVerify(exactly = 0) { executions.ready(any(), any()) }
        coVerify(exactly = 1) { executions.failed("child", any()) }
    }

    @Test
    fun readyFailureKeepsItsCauseEvenWhenFailureRecordingAlsoFails() = runTest {
        val manager = manager()
        val cause = IllegalStateException("READY update affected no rows")
        val recordError = java.io.IOException("failure record unavailable")
        coEvery { executions.ready(any(), any()) } throws cause
        coEvery { executions.failed(any(), any()) } throws recordError
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any(), any()) } answers {
            val command = arg<String>(0)
            CommandResult(gitMetadata(command, arg(3)), 0)
        }
        val failure = runCatching { manager.prepare("child", parent) }.exceptionOrNull() as WorktreeManager.PreparationException
        assertEquals("record_ready", failure.stage)
        assertTrue(failure.cause === cause)
        assertEquals(listOf(recordError), cause.suppressed.toList())
    }

    @Test
    fun mismatchedCheckoutCannotBecomeReady() = runTest {
        val manager = manager()
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any(), any()) } answers {
            val command = arg<String>(0)
            CommandResult(gitMetadata(command, arg(3), head = "b".repeat(40)), 0)
        }
        val failure = runCatching { manager.prepare("child", parent) }.exceptionOrNull() as WorktreeManager.PreparationException
        assertEquals("verify_head", failure.stage)
        assertTrue(failure.message.orEmpty().contains("HEAD mismatch"))
        coVerify(exactly = 0) { executions.ready(any(), any()) }
    }

    @Test fun finalGitViewMustMatchTheTreeRepositoryAndBranchEvenAtTheSameCommit() = runTest {
        val manager = manager()
        for ((property, expectedError) in mapOf("root" to "path mismatch", "common" to "path mismatch", "branch" to "branch mismatch")) {
            coEvery { engine.runCommandSyncUnbounded(any(), any(), any(), any()) } answers {
                val command = arg<String>(0)
                val execution = arg<ExecutionBinding>(3)
                val metadata = if (!command.contains("git symbolic-ref HEAD")) gitMetadata(command, execution)
                    else gitMetadata(command, when (property) {
                        "root" -> execution.copy(executionRoot = parent.executionRoot)
                        "common" -> execution.copy(gitCommonDir = "/other/.git")
                        else -> execution.copy(branch = "other")
                    })
                CommandResult(metadata, 0)
            }
            val failure = runCatching { manager.prepare(property, parent) }.exceptionOrNull() as WorktreeManager.PreparationException
            assertEquals("verify_head", failure.stage)
            assertTrue(failure.message.orEmpty().contains(expectedError))
            coVerify(exactly = 1) { executions.failed(property, any()) }
        }
        coVerify(exactly = 0) { executions.ready(any(), any()) }
    }
}
