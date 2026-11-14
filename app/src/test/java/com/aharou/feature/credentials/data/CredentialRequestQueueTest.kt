package com.aharou.feature.credentials.data

import com.aharou.feature.credentials.data.CredentialRequestBridge.CredentialRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRequestQueueTest {
    private val queue = CredentialRequestQueue()
    private val first = CredentialRequest("first", "github.com")
    private val second = CredentialRequest("second", "github.com")

    @Test
    fun newRequestsWaitBehindCurrentRequest() {
        assertTrue(queue.enqueue(first))
        assertTrue(queue.enqueue(second))
        assertSame(first, queue.current)
        assertEquals(setOf("first", "second"), queue.requestIds)
        assertSame(first, queue.claim("first"))
        assertTrue(queue.complete(first))
        assertSame(second, queue.current)
    }

    @Test
    fun duplicateRequestDoesNotReplaceItsHostOrPosition() {
        assertTrue(queue.enqueue(first))
        assertFalse(queue.enqueue(first.copy(host = "gitlab.com")))
        assertSame(first, queue.current)
        assertEquals(setOf("first"), queue.requestIds)
    }

    @Test
    fun onlyCurrentRequestCanBeClaimedOnce() {
        queue.enqueue(first)
        queue.enqueue(second)
        assertNull(queue.claim("missing"))
        assertNull(queue.claim("second"))
        assertSame(first, queue.claim("first"))
        assertNull(queue.claim("first"))
        assertNull(queue.claim("second"))
    }

    @Test
    fun delayedAnswerCannotCompleteAnotherRequest() {
        queue.enqueue(first)
        queue.enqueue(second)
        queue.claim("first")
        assertFalse(queue.complete(first.copy()))
        assertTrue(queue.complete(first))
        assertSame(second, queue.claim("second"))
        assertFalse(queue.complete(first))
        assertSame(second, queue.current)
        assertTrue(queue.complete(second))
        assertNull(queue.current)
    }

    @Test
    fun failedAnswerCanRetryWithoutAdvancingTheQueue() {
        queue.enqueue(first)
        queue.enqueue(second)
        queue.claim("first")
        queue.retry(first)
        assertSame(first, queue.current)
        assertSame(first, queue.claim("first"))
        assertTrue(queue.complete(first))
        assertSame(second, queue.current)
    }

    @Test
    fun unrelatedRetryDoesNotReleaseTheActiveAnswer() {
        queue.enqueue(first)
        queue.claim("first")
        queue.retry(first.copy())
        assertNull(queue.claim("first"))
        assertTrue(queue.complete(first))
    }

    @Test
    fun completedRequestCannotBeEnqueuedOrAnsweredAgain() {
        queue.enqueue(first)
        queue.claim("first")
        assertTrue(queue.complete(first))
        assertFalse(queue.enqueue(first.copy()))
        assertNull(queue.claim("first"))
        assertFalse(queue.complete(first))
    }

    @Test
    fun missingRequestsAdvanceAndAreReclaimedOnlyOnce() {
        queue.enqueue(first)
        queue.enqueue(second)
        assertEquals(1, queue.retain(setOf("second")))
        assertSame(second, queue.current)
        assertEquals(0, queue.retain(setOf("second")))
        assertFalse(queue.enqueue(first))
        assertEquals(1, queue.retain(emptySet()))
        assertNull(queue.current)
    }

    @Test
    fun requestBeingAnsweredIsRetainedUntilItsAnswerFinishes() {
        queue.enqueue(first)
        queue.enqueue(second)
        queue.claim("first")
        assertEquals(1, queue.retain(emptySet()))
        assertSame(first, queue.current)
        assertTrue(queue.complete(first))
        assertNull(queue.current)
        assertEquals(0, queue.retain(emptySet()))
    }

    @Test
    fun pendingRequestsAreNotEvictedByTheCompletedHistoryLimit() {
        repeat(70) { index -> assertTrue(queue.enqueue(CredentialRequest("request-$index", "github.com"))) }
        assertEquals(70, queue.requestIds.size)
        repeat(70) { index ->
            val request = requireNotNull(queue.claim("request-$index"))
            assertEquals("request-$index", queue.current?.requestId)
            assertTrue(queue.complete(request))
        }
        assertNull(queue.current)
    }
}
