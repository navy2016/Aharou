package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageRegeneratorTest {
    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val records = mutableListOf<AgentMessageEntity>()
    private val selections = mutableListOf<Pair<String, Int>>()
    private lateinit var regenerator: MessageRegenerator

    private fun user(id: String = "user", time: Long = 1) = AgentMessageEntity(id, "session", "USER", "request", time)
    private fun reply(id: String = "reply", time: Long = 2) = AgentMessageEntity(id, "session", "ASSISTANT", "answer", time)

    @Before fun prepare() {
        coEvery { dao.getMessagesBySessionOnce("session") } coAnswers { records.sortedBy { it.timestamp }.toList() }
        coEvery { persistence.restore(any()) } coAnswers { firstArg() }
        coEvery { dao.assignVariant(any(), any(), any(), any()) } coAnswers {
            val ids = firstArg<List<String>>()
            val group = secondArg<String>()
            val index = thirdArg<Int>()
            val excluded = arg<Boolean>(3)
            records.indices.forEach { i ->
                if (records[i].id in ids) records[i] = records[i].copy(variantGroupId = group, variantIndex = index, isContextExcluded = excluded)
            }
        }
        coEvery { dao.updateContextExcluded(any(), any()) } coAnswers {
            val ids = firstArg<List<String>>()
            val excluded = secondArg<Boolean>()
            records.indices.forEach { i -> if (records[i].id in ids) records[i] = records[i].copy(isContextExcluded = excluded) }
        }
        regenerator = MessageRegenerator(dao, persistence)
    }

    @Test fun userMessageRegeneratesWithoutDeletingOrDuplicatingInput() = runTest {
        records += user().copy(attachmentsJson = "[{\"fileName\":\"a.txt\"}]")
        records += reply()
        var cancellations = 0
        regenerator.regenerate("session", "user", { cancellations++ }, { input ->
            assertEquals("user", input.id)
            assertEquals(records.first().attachmentsJson, input.attachmentsJson)
            assertTrue(records.single { it.id == "reply" }.isContextExcluded)
            records += reply("new", 3)
        }, { group, position -> selections += group to position })
        assertEquals(1, cancellations)
        assertEquals(1, records.count { it.role == "USER" })
        assertEquals(0, records.single { it.id == "reply" }.variantIndex)
        assertEquals(1, records.single { it.id == "new" }.variantIndex)
        assertEquals("reply" to 1, selections.single())
        coVerify(exactly = 0) { dao.deleteMessageById(any()) }
    }

    @Test fun unansweredLastInputCanBeRegenerated() = runTest {
        records += user()
        regenerator.regenerate("session", "user", {}, { records += reply() }, { group, position -> selections += group to position })
        assertFalse(records.last().isContextExcluded)
        assertEquals(0, records.last().variantIndex)
        assertEquals(0, selections.single().second)
    }

    @Test fun oldTurnAndSystemMessagesAreNotRegenerated() = runTest {
        records += listOf(user("first", 1), reply("old", 2), user("last", 3))
        var called = false
        regenerator.regenerate("session", "first", { called = true }, { called = true }, { _, _ -> called = true })
        regenerator.regenerate("session", "old", { called = true }, { called = true }, { _, _ -> called = true })
        assertFalse(called)
        records += user("notice", 4).copy(content = BACKGROUND_NOTIFICATION_PREFIX + "done")
        assertEquals("last", regenerationUser(records, "last")?.id)
        assertNull(regenerationUser(records, "notice"))
    }

    @Test fun failureRestoresPreviouslySelectedAnswer() = runTest {
        records += user()
        records += reply("first", 2).copy(variantGroupId = "group", variantIndex = 0)
        records += reply("other", 3).copy(variantGroupId = "group", variantIndex = 2, isContextExcluded = true)
        regenerator.regenerate("session", "user", {}, {}, { group, position -> selections += group to position })
        assertFalse(records.single { it.id == "first" }.isContextExcluded)
        assertTrue(records.single { it.id == "other" }.isContextExcluded)
        assertEquals("group" to 0, selections.single())
    }

    @Test fun sparseVariantIndicesSelectCorrectPosition() = runTest {
        records += user()
        records += reply("first", 2).copy(variantGroupId = "group", variantIndex = 0, isContextExcluded = true)
        records += reply("last", 3).copy(variantGroupId = "group", variantIndex = 2)
        regenerator.regenerate("session", "user", {}, { records += reply("new", 4) }, { group, position -> selections += group to position })
        assertEquals(3, records.last().variantIndex)
        assertEquals("group" to 2, selections.single())
    }

    @Test fun cancelledRegenerationRestoresPriorContext() = runTest {
        records += listOf(user(), reply())
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            regenerator.regenerate("session", "user", {}, { started.complete(Unit); finish.await() }, { _, _ -> })
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(records.single { it.id == "reply" }.isContextExcluded)
    }

    @Test fun duplicateClicksDoNotStartConcurrentRegeneration() = runTest {
        records += user()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var restarts = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            regenerator.regenerate("session", "user", {}, { restarts++; started.complete(Unit); finish.await() }, { _, _ -> })
        }
        started.await()
        try {
            regenerator.regenerate("session", "user", {}, { restarts++ }, { _, _ -> })
            assertEquals(1, restarts)
        } finally {
            finish.complete(Unit)
            first.await()
        }
    }
}
