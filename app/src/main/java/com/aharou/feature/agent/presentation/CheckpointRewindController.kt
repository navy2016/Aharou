package com.aharou.feature.agent.presentation

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.CheckpointDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.presentation.component.RewindOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RewindMenuTarget(val sessionId: String, val messageId: String)
internal data class RewindNotice(val sessionId: String, val result: CheckpointManager.RestoreResult)

internal class CheckpointRewindController(
    private val currentSession: () -> String?,
    private val messages: AgentMessageDao,
    private val checkpoints: CheckpointDao,
    private val manager: CheckpointManager,
    private val persistence: MessagePersistenceUseCase,
    private val handoff: SessionTurnHandoff,
    private val stopTurn: suspend (String) -> Boolean,
    private val resetTurn: (String) -> Unit,
) {
    private val menu = MutableStateFlow<RewindMenuTarget?>(null)
    val target = menu.asStateFlow()
    private val timeline = MutableStateFlow<List<CheckpointManager.UndoRecordInfo>>(emptyList())
    val records = timeline.asStateFlow()
    private val feedback = MutableStateFlow<RewindNotice?>(null)
    val notice = feedback.asStateFlow()

    suspend fun open(messageId: String) {
        val owner = RewindMenuTarget(currentSession() ?: return, messageId)
        timeline.value = emptyList()
        menu.value = owner
        val available = manager.listUndoRecords(owner.sessionId)
        if (menu.value == owner && currentSession() == owner.sessionId) timeline.value = available
    }

    fun dismissMenu() { menu.value = null }
    fun dismissNotice() { feedback.value = null }

    suspend fun preview(owner: RewindMenuTarget): CheckpointManager.RewindDiff? {
        if (menu.value != owner || currentSession() != owner.sessionId) return null
        return try {
            val cp = checkpoints.getCheckpointBySessionAndMessage(owner.sessionId, owner.messageId) ?: return null
            manager.previewRewindDiff(owner.sessionId, cp.id).takeIf { menu.value == owner && currentSession() == owner.sessionId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w("CheckpointRewind", "检查点预览不可用", e)
            null
        }
    }

    enum class Outcome { DONE, BUSY, UNAVAILABLE, FAILED }

    suspend fun execute(
        owner: RewindMenuTarget, option: RewindOption, checkpointId: String?, undoId: String?,
        onFill: (String, List<AgentAttachment>) -> Unit,
    ): Outcome {
        val sid = owner.sessionId
        suspend fun validMessage(): AgentMessageEntity? {
            val row = messages.getMessageById(owner.messageId) ?: return null
            if (row.sessionId != sid || row.role != MessageRole.USER.name || row.isCompactionMarker || row.isContextSummary) return null
            if (option.requiresCheckpoint && (checkpointId == null || checkpoints.getCheckpointBySessionAndMessage(sid, owner.messageId)?.id != checkpointId)) return null
            if (option == RewindOption.UNDO_LAST_RESTORE && (undoId == null || manager.listUndoRecords(sid).none { it.id == undoId })) return null
            return row.takeIf { currentSession() == sid }
        }
        return operate(sid) {
            if (validMessage() == null || !stopTurn(sid)) return@operate Outcome.UNAVAILABLE
            val row = validMessage() ?: return@operate Outcome.UNAVAILABLE
            resetTurn(sid)
            manager.setActiveCheckpointId(sid, null)
            val result = when (option) {
                RewindOption.RESTORE_CODE, RewindOption.RESTORE_CODE_AND_CONVERSATION -> manager.restoreCodeToCheckpoint(sid, requireNotNull(checkpointId))
                RewindOption.UNDO_LAST_RESTORE -> manager.undoRestoreTo(sid, requireNotNull(undoId))
                RewindOption.RESTORE_CONVERSATION -> null
            }
            if (result != null && result.failures.isNotEmpty()) {
                publish(sid, result)
                return@operate Outcome.DONE
            }
            if (option == RewindOption.RESTORE_CONVERSATION || option == RewindOption.RESTORE_CODE_AND_CONVERSATION) {
                val restored = persistence.restore(row)
                persistence.rewindConversation(sid, restored.timestamp)
                if (currentSession() == sid) onFill(restored.content, restored.toUIMessage().attachments)
            }
            messages.repairSingletonVariantGroups(sid)
            publish(sid, result)
            Outcome.DONE
        }
    }

    private suspend fun operate(sessionId: String, block: suspend () -> Outcome): Outcome {
        if (currentSession() != sessionId) return Outcome.UNAVAILABLE
        return try {
            handoff.withRestart(sessionId) { block() } ?: Outcome.BUSY
        } catch (e: CancellationException) {
            throw e
        } catch (e: CheckpointManager.TargetUnavailable) {
            Outcome.UNAVAILABLE
        } catch (e: Exception) {
            FileLogger.e("CheckpointRewind", "检查点操作未完成", e)
            Outcome.FAILED
        }
    }

    suspend fun undoTimeline(sessionId: String, recordId: String): Outcome = operate(sessionId) {
        publish(sessionId, manager.undoRestoreTo(sessionId, recordId))
        Outcome.DONE
    }

    private suspend fun publish(sessionId: String, result: CheckpointManager.RestoreResult?) {
        val available = manager.listUndoRecords(sessionId)
        if (currentSession() != sessionId) return
        timeline.value = available
        feedback.value = result?.takeIf { it.conflicts.isNotEmpty() || it.failures.isNotEmpty() }?.let { RewindNotice(sessionId, it) }
    }
}
