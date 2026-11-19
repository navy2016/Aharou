package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.execution.ExecutionBinding
import com.aharou.feature.agent.domain.execution.SessionExecutionRepository
import com.aharou.feature.agent.domain.execution.WorktreeManager
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.subagent.CommunicationEnabledSource
import com.aharou.feature.agent.domain.subagent.MaxRunningSource
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutionWorkspacesTest {
    private var limit = 5
    private val executions = mockk<SessionExecutionRepository>(relaxed = true)
    private val worktrees = mockk<WorktreeManager>()
    private val events = SubAgentEventBus(MaxRunningSource { limit }, CommunicationEnabledSource { true })
    private val files = mockk<FileAccessProvider>()
    private val helper = TaskExecutionWorkspaces(executions, worktrees, events, files)
    private val parent = ExecutionBinding("parent", "/project", "/project", "builtin-alpine", "LOCAL_PROOT")
    private val isolated = parent.copy(id = "isolated", executionRoot = "/worktrees/isolated", isolation = "worktree")
    private val context = AgentContext(null, null, "/project", null, sessionId = "parent", executionBinding = parent)

    init { coEvery { executions.validate(any()) } answers { arg(0) } }

    @Test fun sharedChildInheritsTheParentsCurrentExecutionDirectory() = runTest {
        val prepared = helper.child("child", emptyMap(), context.copy(executionBinding = isolated))
        assertEquals(isolated, prepared.binding)
        coVerify(exactly = 1) { executions.attach("child", isolated, any()) }
        coVerify(exactly = 0) { worktrees.prepare(any(), any()) }
    }

    @Test fun onePipelineTreeIsReusedByAllChildrenAndRework() = runTest {
        coEvery { worktrees.prepare(any(), parent) } returns WorktreeManager.Prepared(isolated, false)
        val group = helper.pipeline(mapOf("isolation" to JsonPrimitive("worktree")), context)
        for (id in listOf("upstream", "downstream", "rework")) assertEquals(isolated, helper.child(id, emptyMap(), group).binding)
        coVerify(exactly = 1) { worktrees.prepare(any(), parent) }
    }

    @Test fun zeroDisablesPreparationBeforeCreatingAWorktree() = runTest {
        limit = 0
        assertTrue(runCatching { helper.pipeline(mapOf("isolation" to JsonPrimitive("worktree")), context) }.isFailure)
        coVerify(exactly = 0) { worktrees.prepare(any(), any()) }
    }

    @Test fun planCannotPrepareAWorktree() = runTest {
        assertTrue(runCatching { helper.child("child", mapOf("isolation" to JsonPrimitive("worktree")), context.copy(mode = AgentMode.PLAN)) }.isFailure)
        coVerify(exactly = 0) { worktrees.prepare(any(), any()) }
    }

    @Test fun disablingDuringPreparationDoesNotFallBackToShared() = runTest {
        coEvery { worktrees.prepare("child", parent) } answers {
            limit = 0
            WorktreeManager.Prepared(isolated, false)
        }
        assertTrue(runCatching { helper.child("child", mapOf("isolation" to JsonPrimitive("worktree")), context) }.isFailure)
        coVerify(exactly = 0) { executions.attach("child", parent, any()) }
    }
}
