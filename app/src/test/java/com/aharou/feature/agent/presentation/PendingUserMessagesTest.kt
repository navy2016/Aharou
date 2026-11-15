package com.aharou.feature.agent.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingUserMessagesTest {
    private val pending = PendingUserMessages()
    private val input = AgentUIMessage("input", MessageRole.USER, "new request", 1)
    private val loaded = ChatMessagesState("session", emptyList(), loaded = true)

    @Test fun acceptedMessageIsVisibleBeforeTheRunnerWritesIt() {
        pending.add("session", input)
        val visible = pending.merge(loaded, pending.state.value).messages.single()
        assertEquals(input.id, visible.id)
        assertEquals(input.content, visible.content)
        assertTrue(visible.isPending)
    }

    @Test fun persistedMessageReplacesTheWaitingBubbleWithoutDuplication() {
        pending.add("session", input)
        val stored = loaded.copy(messages = listOf(input.copy(timestamp = 20)))
        val visible = pending.merge(stored, pending.state.value).messages
        assertEquals(1, visible.size)
        assertFalse(visible.single().isPending)
        assertEquals(20L, visible.single().timestamp)
        assertTrue(pending.state.value.isEmpty())
    }

    @Test fun lateOldAnswerStaysBeforeTheAcceptedInput() {
        pending.add("session", input)
        val oldReply = AgentUIMessage("reply", MessageRole.ASSISTANT, "old answer", 10)
        assertEquals(listOf("reply", "input"),
            pending.merge(loaded.copy(messages = listOf(oldReply)), pending.state.value).messages.map { it.id })
    }

    @Test fun switchingSessionsNeverShowsAnotherPendingMessage() {
        pending.add("session", input)
        assertTrue(pending.merge(loaded.copy(sessionId = "other"), pending.state.value).messages.isEmpty())
        assertFalse(pending.merge(loaded.copy(loaded = false), pending.state.value).loaded)
        assertEquals(input.id, pending.merge(loaded, pending.state.value).messages.single().id)
    }

    @Test fun failedAdmissionKeepsTheMessageWithAnExplicitStatus() {
        pending.add("session", input)
        pending.fail("session", input.id, "not started")
        val visible = pending.merge(loaded, pending.state.value).messages.single()
        assertEquals("not started", visible.error)
        assertEquals(input.content, visible.content)
        pending.remove("session", input.id)
        assertTrue(pending.state.value.isEmpty())
    }
}
