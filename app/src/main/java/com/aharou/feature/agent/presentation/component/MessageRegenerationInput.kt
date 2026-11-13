package com.aharou.feature.agent.presentation.component

import android.content.Context
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.agent.presentation.hasVisibleContent
import com.aharou.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MessageRegenerationInput(
    val text: String,
    val modelRequest: String,
    val attachments: List<AgentAttachment>,
    val images: List<AgentImage>,
)

internal suspend fun regenerationInput(context: Context, message: AgentMessageEntity, access: FileAccessProvider): MessageRegenerationInput =
    withContext(Dispatchers.IO) {
        val attachments = message.toUIMessage().attachments
        val pending = attachments.map { attachment ->
            val restored = if (attachment.isImage) {
                attachment.copy(localPath = resolveLocalFile(attachment, access)?.absolutePath.orEmpty())
            } else attachment
            restored.toPendingAttachment()
        }
        MessageRegenerationInput(message.content, appendAttachmentsToRequest(context, message.content, pending), attachments, pending.toAgentImages())
    }

internal fun canRegenerateMessage(message: AgentUIMessage, all: List<AgentUIMessage>): Boolean {
    if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) return false
    if (all.none { it.id == message.id }) return false
    if (message.isSystemCardInTurn() || (!message.content.hasVisibleContent() && message.attachments.isEmpty())) return false
    val user = all.lastOrNull { it.role == MessageRole.USER && !it.isSystemCardInTurn() } ?: return false
    return message.id == user.id || (message.role == MessageRole.ASSISTANT && message.timestamp > user.timestamp)
}
