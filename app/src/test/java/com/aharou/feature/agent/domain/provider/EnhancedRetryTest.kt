package com.aharou.feature.agent.domain.provider

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class EnhancedRetryTest {
    private val options = AutoRetryOptions(enabled = true, initialDelayMs = 0, maxDelayMs = 0, jitter = false)

    private fun http(code: Int, body: String = "error"): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody()))

    @Test
    fun statusAndKeywordsRespectStopPriority() {
        assertTrue(shouldRetryWithOptions(http(503), options))
        assertFalse(shouldRetryWithOptions(http(503, "insufficient balance"), options))
        assertTrue(shouldRetryWithOptions(http(400, "try again later"), options))
        assertFalse(shouldRetryWithOptions(http(400, "bad request"), options))
        assertTrue(shouldRetryWithOptions(StreamApiException("server_error", "unavailable"), options))
        assertFalse(shouldRetryWithOptions(StreamApiException("insufficient_quota", "quota exceeded"), options))
        assertFalse(shouldRetryWithOptions(CancellationException(), options))
    }

    @Test
    fun networkToggleAndBackoffAreApplied() {
        assertFalse(shouldRetryWithOptions(IOException("connection reset"), options.copy(retryOnNetworkError = false)))
        val fixed = options.copy(initialDelayMs = 1000, maxDelayMs = 3000, multiplier = 2.0)
        assertEquals(1000L, retryDelayMillis(0, IOException(), fixed))
        assertEquals(2000L, retryDelayMillis(1, IOException(), fixed))
        assertEquals(3000L, retryDelayMillis(2, IOException(), fixed))
    }

    @Test
    fun extraAttemptsAreLimitedAndStreamDoesNotReplayContent() = runTest {
        var attempts = 0
        val result = retryStaircase(retryOptions = options.copy(maxRetries = 2)) {
            attempts++
            if (attempts < 3) throw IOException("connection reset")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)

        attempts = 0
        try {
            streamWithStaircaseRetry(
                retryOptions = options,
                attemptOnce = { received ->
                    attempts++
                    received()
                    throw IOException("connection reset")
                }
            )
        } catch (_: IOException) {
            // The partial response must remain the only attempt.
        }
        assertEquals(1, attempts)
    }
}
