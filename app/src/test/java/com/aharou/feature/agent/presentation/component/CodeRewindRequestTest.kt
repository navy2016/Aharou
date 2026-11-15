package com.aharou.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeRewindRequestTest {
    @Test fun closingTheMenuDoesNotEraseTheConfirmationTarget() {
        var menuTarget: String? = "message"
        val request = CodeRewindRequest("session", requireNotNull(menuTarget), 1234L, RewindOption.RESTORE_CODE, 2)
        menuTarget = null
        assertEquals(null, menuTarget)
        assertEquals("message", request.messageId)
        assertEquals(1234L, request.timestamp)
        assertEquals(2, request.fileCount)
    }

    @Test fun confirmationRetainsBothCodeRestoreOptions() {
        for (option in listOf(RewindOption.RESTORE_CODE, RewindOption.RESTORE_CODE_AND_CONVERSATION)) {
            val request = CodeRewindRequest("session", "message", 1234L, option, 2)
            assertEquals(option, request.option)
            assertTrue(request.belongsTo("session"))
        }
    }

    @Test fun anotherSessionCannotUseThePendingConfirmation() {
        val request = CodeRewindRequest("session", "message", 1234L, RewindOption.RESTORE_CODE, 2)
        assertFalse(request.belongsTo("other"))
        assertFalse(request.belongsTo(null))
    }
}
