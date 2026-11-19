package com.aharou.feature.agent.presentation.component

import com.aharou.feature.agent.domain.checkpoint.CheckpointManager.RewindDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeRewindRequestTest {
    @Test fun closingTheMenuDoesNotEraseTheConfirmationTarget() {
        var menuTarget: String? = "message"
        val request = CodeRewindRequest("session", requireNotNull(menuTarget), 1234L, RewindOption.RESTORE_CODE, RewindDiff("cp", emptyList(), 2, 1, 0, false))
        menuTarget = null
        assertEquals(null, menuTarget)
        assertEquals("message", request.messageId)
        assertEquals(1234L, request.timestamp)
        assertEquals("cp", request.checkpointId)
        assertEquals(2, request.restoreCount)
    }

    @Test fun confirmationRetainsBothCodeRestoreOptions() {
        for (option in listOf(RewindOption.RESTORE_CODE, RewindOption.RESTORE_CODE_AND_CONVERSATION)) {
            val request = CodeRewindRequest("session", "message", 1234L, option, RewindDiff("cp", emptyList(), 2, 1, 0, false))
            assertEquals(option, request.option)
            assertTrue(request.belongsTo("session"))
        }
    }

    @Test fun confirmationUsesPreviewCountsIncludingACompletelySkippedOrPartialSelection() {
        for ((restored, skipped, partial) in listOf(Triple(0, 2, false), Triple(1, 1, false), Triple(199, 1, true))) {
            val preview = RewindDiff("target", emptyList(), restored, restored, skipped, partial)
            val request = CodeRewindRequest("session", "message", 1234L, RewindOption.RESTORE_CODE, preview)
            assertEquals(restored, request.restoreCount)
            assertEquals(skipped, request.skipCount)
            assertEquals(partial, request.partial)
            assertEquals("target", request.checkpointId)
        }
    }

    @Test fun anotherSessionCannotUseThePendingConfirmation() {
        val request = CodeRewindRequest("session", "message", 1234L, RewindOption.RESTORE_CODE, RewindDiff("cp", emptyList(), 2, 1, 0, false))
        assertFalse(request.belongsTo("other"))
        assertFalse(request.belongsTo(null))
    }
}
