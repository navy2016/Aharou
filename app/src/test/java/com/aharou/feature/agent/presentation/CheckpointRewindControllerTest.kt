package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.CheckpointDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.data.local.entity.CheckpointEntity
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.presentation.CheckpointRewindController.Outcome
import com.aharou.feature.agent.presentation.component.RewindOption
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CheckpointRewindControllerTest {
    private val messages = mockk<AgentMessageDao>(relaxed = true)
    private val checkpoints = mockk<CheckpointDao>()
    private val manager = mockk<CheckpointManager>(relaxed = true)
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val owner = RewindMenuTarget("s", "m")
    private val message = AgentMessageEntity("m", "s", "USER", "draft", 7)
    private val cp = CheckpointEntity("cp", "s", "m", "draft")
    private var session = "s"
    private var stops = 0
    private var resets = 0
    private val controller = CheckpointRewindController({ session }, messages, checkpoints, manager, persistence,
        SessionTurnHandoff(mutableMapOf(), mutableMapOf()), { stops++; true }, { resets++ })

    init {
        coEvery { messages.getMessageById("m") } returns message
        coEvery { checkpoints.getCheckpointBySessionAndMessage("s", "m") } returns null
        coEvery { manager.listUndoRecords("s") } returns emptyList()
    }

    @Test fun bothCodeOptionsRequireTheExactCheckpointBeforeStoppingOrDeletingMessages() = runTest {
        for (option in listOf(RewindOption.RESTORE_CODE, RewindOption.RESTORE_CODE_AND_CONVERSATION)) {
            for (expected in listOf(null, "gone")) {
                assertEquals(Outcome.UNAVAILABLE, controller.execute(owner, option, expected, null) { _, _ -> fail() })
            }
        }
        coEvery { checkpoints.getCheckpointBySessionAndMessage("s", "m") } returns cp.copy(id = "replacement")
        assertEquals(Outcome.UNAVAILABLE, controller.execute(owner, RewindOption.RESTORE_CODE_AND_CONVERSATION, "cp", null) { _, _ -> fail() })
        assertEquals(0, stops); assertEquals(0, resets)
        verify(exactly = 0) { manager.setActiveCheckpointId(any(), any()) }
        coVerify(exactly = 0) { persistence.rewindConversation(any(), any()) }
    }

    @Test fun emptyUndoAndObsoleteNodeCannotCancelOrChooseTheNewestBackup() = runTest {
        assertEquals(Outcome.UNAVAILABLE, controller.execute(owner, RewindOption.UNDO_LAST_RESTORE, null, null) { _, _ -> fail() })
        coEvery { manager.listUndoRecords("s") } returns listOf(CheckpointManager.UndoRecordInfo("newer", "restore", 1L, 1))
        assertEquals(Outcome.UNAVAILABLE, controller.execute(owner, RewindOption.UNDO_LAST_RESTORE, null, "gone") { _, _ -> fail() })
        assertEquals(0, stops); assertEquals(0, resets)
        coVerify(exactly = 0) { manager.undoRestoreTo(any(), any()) }
    }

    @Test fun explicitConversationOnlyIsStillAvailableWithoutACodeCheckpoint() = runTest {
        coEvery { persistence.restore(message) } returns message
        var draft: String? = null
        assertEquals(Outcome.DONE, controller.execute(owner, RewindOption.RESTORE_CONVERSATION, null, null) { text, _ -> draft = text })
        assertEquals("draft", draft); assertEquals(1, stops)
        coVerify(exactly = 1) { persistence.rewindConversation("s", 7) }
        coVerify(exactly = 0) { manager.restoreCodeToCheckpoint(any(), any()) }
    }

    @Test fun checkpointDisappearingDuringHandoffCannotFallThroughToConversationOnly() = runTest {
        coEvery { checkpoints.getCheckpointBySessionAndMessage("s", "m") } answers { if (stops == 0) cp else null }
        assertEquals(Outcome.UNAVAILABLE, controller.execute(owner, RewindOption.RESTORE_CODE_AND_CONVERSATION, "cp", null) { _, _ -> fail() })
        assertEquals(1, stops); assertEquals(0, resets)
        coVerify(exactly = 0) { persistence.rewindConversation(any(), any()) }
    }

    @Test fun bothUndoEntrancesExposeSkippedAndFailedPathsFromTheExactRecord() = runTest {
        val record = CheckpointManager.UndoRecordInfo("chosen", "restore", 1L, 3)
        val result = CheckpointManager.RestoreResult(1, listOf("manual"), listOf("refused"))
        coEvery { manager.listUndoRecords("s") } returns listOf(record)
        coEvery { manager.undoRestoreTo("s", "chosen") } returns result
        assertEquals(Outcome.DONE, controller.execute(owner, RewindOption.UNDO_LAST_RESTORE, null, record.id) { _, _ -> fail() })
        assertEquals(RewindNotice("s", result), controller.notice.value)
        controller.dismissNotice()
        assertEquals(Outcome.DONE, controller.undoTimeline("s", record.id))
        assertEquals(RewindNotice("s", result), controller.notice.value)
    }

    @Test fun failedCodeWriteDoesNotTruncateTheConversation() = runTest {
        coEvery { checkpoints.getCheckpointBySessionAndMessage("s", "m") } returns cp
        val failed = CheckpointManager.RestoreResult(1, emptyList(), listOf("refused"))
        coEvery { manager.restoreCodeToCheckpoint("s", "cp") } returns failed
        assertEquals(Outcome.DONE, controller.execute(owner, RewindOption.RESTORE_CODE_AND_CONVERSATION, "cp", null) { _, _ -> fail() })
        assertEquals(RewindNotice("s", failed), controller.notice.value)
        coVerify(exactly = 0) { persistence.rewindConversation(any(), any()) }
    }

    @Test fun anotherSessionDoesNotReceiveLateUndoFeedback() = runTest {
        coEvery { manager.undoRestoreTo("s", "chosen") } answers {
            session = "other"
            CheckpointManager.RestoreResult(0, listOf("private file"))
        }
        assertEquals(Outcome.DONE, controller.undoTimeline("s", "chosen"))
        assertNull(controller.notice.value)
        assertEquals(Outcome.UNAVAILABLE, controller.undoTimeline("s", "chosen"))
        coVerify(exactly = 1) { manager.undoRestoreTo("s", "chosen") }
    }
}
