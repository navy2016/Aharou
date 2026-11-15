package com.aharou.feature.agent.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionTurnHandoffTest {
    private val jobs = mutableMapOf<String, Job>()
    private val stopping = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val handoff = SessionTurnHandoff(jobs, stopping)

    @Test fun softStoppedTurnFinishesWithoutASecondCancellation() = runTest {
        val old = CompletableDeferred<Unit>()
        val stop = CompletableDeferred<Unit>()
        jobs["session"] = old
        stopping["session"] = stop
        val restart = async(start = CoroutineStart.UNDISPATCHED) { handoff.settleForRestart("session") }
        assertTrue(old.isActive)
        assertFalse(restart.isCompleted)
        old.complete(Unit)
        stopping.remove("session")
        stop.complete(Unit)
        assertTrue(restart.await())
        assertFalse(old.isCancelled)
    }

    @Test fun alreadyCancellingTurnMustFinishItsTail() = runTest {
        val tail = CompletableDeferred<Unit>()
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { tail.await() } }
        }
        jobs["session"] = old
        old.cancel()
        runCurrent()
        assertFalse(old.isActive)
        assertFalse(old.isCompleted)
        val restart = async(start = CoroutineStart.UNDISPATCHED) { handoff.settleForRestart("session") }
        assertFalse(restart.isCompleted)
        tail.complete(Unit)
        assertTrue(restart.await())
        assertTrue(old.isCompleted)
    }

    @Test fun strongStopReplacingSoftStopStillHoldsTheBarrier() = runTest {
        val old = CompletableDeferred<Unit>()
        val soft = CompletableDeferred<Unit>()
        val strong = CompletableDeferred<Unit>()
        jobs["session"] = old
        stopping["session"] = soft
        val restart = async(start = CoroutineStart.UNDISPATCHED) { handoff.settleForRestart("session") }
        stopping["session"] = strong
        soft.complete(Unit)
        old.complete(Unit)
        runCurrent()
        assertFalse(restart.isCompleted)
        stopping.remove("session")
        strong.complete(Unit)
        assertTrue(restart.await())
    }

    @Test fun aNewJobCannotBeClearedByAnOldRestart() = runTest {
        val old = CompletableDeferred<Unit>()
        val replacement = CompletableDeferred<Unit>()
        val stop = CompletableDeferred<Unit>()
        jobs["session"] = old
        stopping["session"] = stop
        val restart = async(start = CoroutineStart.UNDISPATCHED) { handoff.settleForRestart("session") }
        jobs["session"] = replacement
        old.complete(Unit)
        stopping.remove("session")
        stop.complete(Unit)
        assertFalse(restart.await())
        assertTrue(jobs["session"] === replacement)
        replacement.complete(Unit)
    }

    @Test fun duplicateRestartIsRejectedButItsOwnerCanStart() = runTest {
        val finish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.withRestart("session") { ticket ->
                handoff.awaitIdle("session", requireNotNull(currentCoroutineContext()[Job]), ticket)
                events += "owner"
                finish.await()
            }
        }
        assertNull(handoff.withRestart("session") { events += "duplicate" })
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.awaitIdle("session", requireNotNull(currentCoroutineContext()[Job]))
            events += "next"
        }
        assertEquals(listOf("owner"), events)
        assertFalse(next.isCompleted)
        finish.complete(Unit)
        first.await()
        next.await()
        assertEquals(listOf("owner", "next"), events)
    }

    @Test fun restartReservationIsRecheckedAfterTheStopBarrier() = runTest {
        val stop = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        stopping["session"] = stop
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.awaitIdle("session", requireNotNull(currentCoroutineContext()[Job]))
        }
        val restart = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.withRestart("session") { finish.await() }
        }
        stopping.remove("session")
        stop.complete(Unit)
        runCurrent()
        assertFalse(next.isCompleted)
        finish.complete(Unit)
        restart.await()
        next.await()
    }

    @Test fun failedRestartReleasesWaitersWithoutBlockingAnotherSession() = runTest {
        val finish = CompletableDeferred<Unit>()
        val restart = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { handoff.withRestart("session") { finish.await(); error("failed") } }
        }
        handoff.awaitIdle("other", requireNotNull(currentCoroutineContext()[Job]))
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            handoff.awaitIdle("session", requireNotNull(currentCoroutineContext()[Job]))
        }
        assertFalse(next.isCompleted)
        finish.complete(Unit)
        assertTrue(restart.await().isFailure)
        next.await()
    }
}
