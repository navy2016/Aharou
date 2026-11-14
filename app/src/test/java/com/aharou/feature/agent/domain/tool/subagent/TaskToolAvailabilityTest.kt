package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.CommunicationEnabledSource
import com.aharou.feature.agent.domain.subagent.MaxRunningSource
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.subagent.SubAgentEvent
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.domain.subagent.SubAgentWriteLease
import com.aharou.feature.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskToolAvailabilityTest {
    private var limit = 0
    private val sessions = mockk<SessionUseCase>(relaxed = true)
    private val dao = mockk<ChatSessionDao>(relaxed = true)
    private val bus = SubAgentEventBus(MaxRunningSource { limit }, CommunicationEnabledSource { true })
    private val tool = TaskTool(sessions, dao, mockk(relaxed = true), bus, mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), SubAgentWriteLease(), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true))
    private val context = AgentContext(null, null, "/project", null, sessionId = "parent")

    @Before
    fun setUp() {
        coEvery { sessions.getSessionById("sub") } returns ChatSessionEntity("sub", "child", 1, 1, parentId = "parent")
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        every { FileLogger.w(any(), any(), any()) } returns Unit
    }

    @After
    fun tearDown() { unmockkObject(FileLogger) }

    @Test
    fun zeroRejectsAllLaunchActionsBeforeCreatingSessions() = runTest {
        for (action in listOf("create", "pipeline", "team_run")) {
            val result = tool.executeWithContext(mapOf("action" to JsonPrimitive(action)), context)
            assertEquals("SUBAGENTS_DISABLED", (result as ToolResult.Error).code)
        }
        coVerify(exactly = 0) { sessions.upsertSession(any()) }
        assertEquals(0, bus.activeCount)
        assertFalse(tool.description.contains("默认直接派"))
    }

    @Test
    fun zeroBlocksIdleSendButAllowsCoordinationForAnAlreadyRunningTurn() = runTest {
        val args = mapOf("action" to JsonPrimitive("send"), "id" to JsonPrimitive("sub"), "message" to JsonPrimitive("update"))
        assertEquals("SUBAGENTS_DISABLED", (tool.executeWithContext(args, context) as ToolResult.Error).code)
        limit = 1
        val run = requireNotNull(bus.beginRun("sub", "parent", 1))
        limit = 0
        assertTrue(tool.executeWithContext(args, context) is ToolResult.Success)
        assertTrue(bus.isCurrentRun(run))
        bus.finishRun(run, SubAgentEvent("sub", "parent", SubAgentEventType.COMPLETED, runId = run.runId))
        assertEquals("SUBAGENTS_DISABLED", (tool.executeWithContext(args, context) as ToolResult.Error).code)
    }

    @Test
    fun zeroKeepsTaskManagementAvailable() = runTest {
        for (action in listOf("read", "list", "stop", "del")) {
            val result = tool.executeWithContext(mapOf("action" to JsonPrimitive(action), "id" to JsonPrimitive("sub")), context)
            assertTrue("$action: $result", result is ToolResult.Success)
        }
    }
}
