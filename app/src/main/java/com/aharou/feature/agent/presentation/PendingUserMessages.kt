package com.aharou.feature.agent.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class PendingUserMessages {
    private val pending = MutableStateFlow<Map<String, List<AgentUIMessage>>>(emptyMap())
    val state = pending.asStateFlow()

    fun add(sessionId: String, message: AgentUIMessage) {
        pending.update { all -> all + (sessionId to (all[sessionId].orEmpty() + message.copy(isPending = true))) }
    }

    fun remove(sessionId: String, messageId: String) {
        pending.update { all ->
            val remaining = all[sessionId].orEmpty().filterNot { it.id == messageId }
            if (remaining.isEmpty()) all - sessionId else all + (sessionId to remaining)
        }
    }

    fun fail(sessionId: String, messageId: String, error: String) {
        pending.update { all ->
            val entries = all[sessionId] ?: return@update all
            all + (sessionId to entries.map { if (it.id == messageId) it.copy(error = error) else it })
        }
    }

    fun merge(messages: ChatMessagesState, snapshot: Map<String, List<AgentUIMessage>>): ChatMessagesState {
        val sessionId = messages.sessionId ?: return messages
        if (!messages.loaded) return messages
        val existing = messages.messages.mapTo(HashSet()) { it.id }
        val entries = snapshot[sessionId].orEmpty()
        entries.filter { it.id in existing }.forEach { remove(sessionId, it.id) }
        val waiting = entries.filterNot { it.id in existing }
        return if (waiting.isEmpty()) messages else messages.copy(messages = messages.messages + waiting)
    }
}
