package com.aharou.feature.agent.presentation.component

import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.MessageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRegenerationInputTest {
    private val first = AgentUIMessage("first", MessageRole.USER, "first", 1)
    private val firstReply = AgentUIMessage("first-reply", MessageRole.ASSISTANT, "reply", 2)
    private val last = AgentUIMessage("last", MessageRole.USER, "last", 3)

    @Test fun onlyLastTurnUserAndAssistantHaveRegenerateAction() {
        val lastReply = AgentUIMessage("last-reply", MessageRole.ASSISTANT, "reply", 4)
        val all = listOf(first, firstReply, last, lastReply)
        assertFalse(canRegenerateMessage(first, all))
        assertFalse(canRegenerateMessage(firstReply, all))
        assertTrue(canRegenerateMessage(last, all))
        assertTrue(canRegenerateMessage(lastReply, all))
    }

    @Test fun attachmentOnlyLastUserCanRegenerateBeforeAnyAnswer() {
        val attachment = AgentAttachment("a.txt", "~/workspace/a.txt", "", "text/plain", 1, false)
        val input = last.copy(content = "", attachments = listOf(attachment))
        assertTrue(canRegenerateMessage(input, listOf(first, firstReply, input)))
    }

    @Test fun messageFromPreviousSessionHasNoRegenerateAction() {
        val stale = AgentUIMessage("stale", MessageRole.ASSISTANT, "old", 5)
        assertFalse(canRegenerateMessage(stale, listOf(last)))
    }

    @Test fun backgroundNotificationsDoNotDisplaceLastUserInput() {
        val notice = AgentUIMessage("notice", MessageRole.USER, "done", 4, isBackgroundNotification = true)
        val all = listOf(last, notice)
        assertTrue(canRegenerateMessage(last, all))
        assertFalse(canRegenerateMessage(notice, all))
    }
}
