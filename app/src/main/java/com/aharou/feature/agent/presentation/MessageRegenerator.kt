package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class MessageRegenerator @Inject constructor(
    private val messages: AgentMessageDao,
    private val persistence: MessagePersistenceUseCase,
) {
    private val runningSessions = ConcurrentHashMap.newKeySet<String>()

    suspend fun regenerate(
        sessionId: String,
        messageId: String,
        cancelTurn: suspend () -> Unit,
        restart: suspend (AgentMessageEntity) -> Unit,
        selectVariant: (String, Int) -> Unit,
    ) {
        if (!runningSessions.add(sessionId)) return
        try {
            if (regenerationUser(messages.getMessagesBySessionOnce(sessionId), messageId) == null) return
            cancelTurn()
            val all = messages.getMessagesBySessionOnce(sessionId)
            val user = regenerationUser(all, messageId) ?: return
            val restored = persistence.restore(user)
            val oldTurn = all.filter { it.timestamp > user.timestamp && it.role != MessageRole.USER.name }
            val groupId = all.firstOrNull { it.id == messageId }?.variantGroupId
                ?: oldTurn.firstNotNullOfOrNull { it.variantGroupId }
                ?: oldTurn.firstOrNull { it.role == MessageRole.ASSISTANT.name }?.id
                ?: UUID.randomUUID().toString()
            val indices = oldTurn.filter { it.variantGroupId == groupId }.map { it.variantIndex }.distinct().sorted()
            val activeIndex = oldTurn.firstOrNull { it.variantGroupId == groupId && !it.isContextExcluded }?.variantIndex
                ?: indices.lastOrNull() ?: 0
            val previousActiveIds = oldTurn.filter { !it.isContextExcluded }.map { it.id }
            val ungrouped = oldTurn.filter { it.variantGroupId == null }.map { it.id }
            val oldIds = oldTurn.map { it.id }
            val nextIndex = if (indices.isEmpty()) { if (oldTurn.isEmpty()) 0 else 1 } else indices.last() + 1
            val knownIds = all.mapTo(HashSet()) { it.id }
            try {
                if (ungrouped.isNotEmpty()) messages.assignVariant(ungrouped, groupId, activeIndex, excluded = true)
                if (oldIds.isNotEmpty()) messages.updateContextExcluded(oldIds, excluded = true)
                persistence.invalidateHistory(sessionId)
                restart(restored)
            } finally {
                withContext(NonCancellable) {
                    val current = messages.getMessagesBySessionOnce(sessionId)
                    val laterUser = current.firstOrNull { it.id !in knownIds && it.isUserInput() }
                    val generated = current.filter {
                        it.id !in knownIds && it.role != MessageRole.USER.name &&
                            (laterUser == null || it.timestamp < laterUser.timestamp)
                    }
                    if (generated.any { it.content.hasVisibleContent() || !it.attachmentsJson.isNullOrBlank() || !it.reasoning.isNullOrBlank() }) {
                        messages.assignVariant(generated.map { it.id }, groupId, nextIndex, excluded = false)
                        val positions = (indices + (if (ungrouped.isNotEmpty()) listOf(activeIndex) else emptyList()) + nextIndex).distinct().sorted()
                        selectVariant(groupId, positions.indexOf(nextIndex))
                    } else {
                        if (previousActiveIds.isNotEmpty()) messages.updateContextExcluded(previousActiveIds, excluded = false)
                        selectVariant(groupId, (indices.indexOf(activeIndex)).coerceAtLeast(0))
                    }
                    persistence.invalidateHistory(sessionId)
                }
            }
        } finally {
            runningSessions.remove(sessionId)
        }
    }
}

internal fun regenerationUser(all: List<AgentMessageEntity>, messageId: String): AgentMessageEntity? {
    val target = all.firstOrNull { it.id == messageId } ?: return null
    if (target.role != MessageRole.USER.name && target.role != MessageRole.ASSISTANT.name) return null
    if (target.isCompactionMarker || target.isContextSummary || target.content.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) return null
    val user = all.lastOrNull { it.isUserInput() } ?: return null
    return user.takeIf { target.id == it.id || (target.role == MessageRole.ASSISTANT.name && target.timestamp > it.timestamp) }
}

private fun AgentMessageEntity.isUserInput(): Boolean =
    role == MessageRole.USER.name && !isCompactionMarker && !isContextSummary && !content.startsWith(BACKGROUND_NOTIFICATION_PREFIX)
