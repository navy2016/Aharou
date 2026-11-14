package com.aharou.feature.agent.domain.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceFileLockTest {
    private val locks = WorkspaceFileLock()

    @Test
    fun samePathWaitsForTheCurrentWriter() = runTest {
        val release = CompletableDeferred<Unit>()
        val entered = mutableListOf<String>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("path") { entered += "first"; release.await() }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("path") { entered += "second" }
        }
        assertEquals(listOf("first"), entered)
        assertEquals(1, locks.trackedKeyCount())
        release.complete(Unit)
        joinAll(first, second)
        assertEquals(listOf("first", "second"), entered)
        assertEquals(0, locks.trackedKeyCount())
    }

    @Test
    fun distinctPathsDoNotBlockEachOther() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("first") { release.await() }
        }
        assertEquals("second", locks.withLock("second") { "second" })
        assertEquals(1, locks.trackedKeyCount())
        release.complete(Unit)
        first.join()
        assertEquals(0, locks.trackedKeyCount())
    }

    @Test
    fun cancellingAWaitingWriterReleasesItsReference() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("path") { release.await() }
        }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("path") { fail("Cancelled waiter entered the lock") }
        }
        waiter.cancelAndJoin()
        assertEquals(1, locks.trackedKeyCount())
        release.complete(Unit)
        first.join()
        assertEquals(0, locks.trackedKeyCount())
        assertEquals("reacquired", locks.withLock("path") { "reacquired" })
        assertEquals(0, locks.trackedKeyCount())
    }

    @Test
    fun cancellingAfterOneOfMultipleKeysReleasesBothReferences() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("b") { release.await() }
        }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock(listOf("b", "a")) { fail("Cancelled waiter entered both locks") }
        }
        assertEquals(2, locks.trackedKeyCount())
        waiter.cancelAndJoin()
        assertEquals(1, locks.trackedKeyCount())
        assertEquals("a", locks.withLock("a") { "a" })
        release.complete(Unit)
        first.join()
        assertEquals(0, locks.trackedKeyCount())
    }

    @Test
    fun cancellingAHolderLetsTheNextWriterEnter() = runTest {
        val entered = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("path") { CompletableDeferred<Unit>().await() }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("path") { entered.complete(Unit) }
        }
        first.cancelAndJoin()
        entered.await()
        second.join()
        assertEquals(0, locks.trackedKeyCount())
    }

    @Test
    fun overlappingKeysUseTheSameOrderAndIgnoreDuplicates() = runTest {
        val release = CompletableDeferred<Unit>()
        val entered = mutableListOf<String>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock(listOf("b", "a", "a", null, " ")) {
                entered += "first"
                release.await()
            }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock(listOf("a", "b")) { entered += "second" }
        }
        assertEquals(2, locks.trackedKeyCount())
        assertEquals(listOf("first"), entered)
        release.complete(Unit)
        joinAll(first, second)
        assertEquals(listOf("first", "second"), entered)
        assertEquals(0, locks.trackedKeyCount())
    }

    @Test
    fun thrownFailureReleasesEveryKeyAndPreservesTheFailure() = runTest {
        val expected = IllegalStateException("fixture")
        try {
            locks.withLock(listOf("a", "b")) { throw expected }
            fail("Expected the block failure")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }
        assertEquals(0, locks.trackedKeyCount())
        assertEquals("ok", locks.withLock(listOf("b", "a")) { "ok" })
    }

    @Test
    fun releasingAndReacquiringOnMultipleThreadsNeverCreatesTwoHolders() = runBlocking {
        val inside = AtomicInteger()
        val entered = AtomicInteger()
        val start = CompletableDeferred<Unit>()
        Executors.newFixedThreadPool(4).asCoroutineDispatcher().use { dispatcher ->
            withTimeout(10_000) {
                val jobs = List(4) {
                    launch(dispatcher) {
                        start.await()
                        repeat(500) {
                            locks.withLock("path") {
                                assertEquals(1, inside.incrementAndGet())
                                try { entered.incrementAndGet(); Thread.yield() }
                                finally { inside.decrementAndGet() }
                            }
                        }
                    }
                }
                start.complete(Unit)
                jobs.joinAll()
            }
        }
        assertEquals(2000, entered.get())
        assertEquals(0, inside.get())
        assertEquals(0, locks.trackedKeyCount())
    }

    @Test
    fun emptyKeysExecuteWithoutRegisteringALock() = runTest {
        assertEquals("ok", locks.withLock(listOf(null, "", " ")) { "ok" })
        assertEquals(0, locks.trackedKeyCount())
    }
}
