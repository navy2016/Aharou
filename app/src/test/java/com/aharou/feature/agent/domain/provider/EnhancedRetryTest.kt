package com.aharou.feature.agent.domain.provider

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class EnhancedRetryTest {
    private val options = AutoRetryOptions(enabled = true, initialDelayMs = 0, maxDelayMs = 0, jitter = false)

    private fun http(code: Int, body: String = "error"): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody()))

    @Test
    fun retryCountNormalizationPreservesDefaultAndBounds() {
        assertEquals(3, AutoRetryOptions().maxRetries)
        assertEquals(3, AutoRetryOptions().normalized().maxRetries)
        assertEquals(0, options.copy(maxRetries = -1).normalized().maxRetries)
        assertEquals(0, options.copy(maxRetries = 0).normalized().maxRetries)
        assertEquals(100, options.copy(maxRetries = 100).normalized().maxRetries)
        assertEquals(100, options.copy(maxRetries = 101).normalized().maxRetries)
    }

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
    fun stopKeywordPreventsKeyFailover() = runTest {
        val error = http(402, "insufficient balance")
        var attempts = 0
        var keyFailures = 0
        val failure = runCatching {
            retryStaircase(
                retryOptions = options,
                onKeyFailure = { _, _ -> ++keyFailures < 2 },
            ) {
                attempts++
                throw error
            }
        }.exceptionOrNull()
        assertSame(error, failure)
        assertEquals(1, attempts)
        assertEquals(0, keyFailures)
    }

    @Test
    fun streamStopKeywordPreventsKeyFailover() = runTest {
        val error = StreamApiException("insufficient_quota", "quota exceeded")
        var attempts = 0
        var keyFailures = 0
        val failure = runCatching {
            streamWithStaircaseRetry(
                retryOptions = options,
                onKeyFailure = { _, _ -> ++keyFailures < 2 },
                attemptOnce = {
                    attempts++
                    throw error
                },
            )
        }.exceptionOrNull()
        assertSame(error, failure)
        assertEquals(1, attempts)
        assertEquals(0, keyFailures)
    }

    @Test
    fun disabledEnhancementKeepsKeyFailover() = runTest {
        var attempts = 0
        var keyFailures = 0
        val result = retryStaircase(
            retryOptions = options.copy(enabled = false),
            onKeyFailure = { _, _ -> ++keyFailures < 2 },
        ) {
            if (++attempts == 1) throw http(402, "insufficient balance")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(1, keyFailures)
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
    fun highRetryIndexesKeepBackoffCapped() {
        val error = IOException("connection reset")
        for (multiplier in listOf(2.0, AutoRetryOptions.MAX_MULTIPLIER)) {
            val fixed = options.copy(initialDelayMs = 1000, maxDelayMs = 3000, multiplier = multiplier)
            for (retryIndex in 2 until 100) {
                assertEquals(
                    "retryIndex=$retryIndex, multiplier=$multiplier",
                    3000L,
                    retryDelayMillis(retryIndex, error, fixed),
                )
            }
        }
    }

    @Test
    fun retryBudgetCountsOnlyAdditionalAttempts() = runTest {
        val error = IOException("connection reset")
        for ((maxRetries, expectedAttempts) in listOf(0 to 1, 100 to 101, 101 to 101)) {
            var attempts = 0
            val retries = mutableListOf<Int>()
            val failure = runCatching {
                retryStaircase(
                    retryOptions = options.copy(maxRetries = maxRetries),
                    onRetry = { attempt, maximum, _ ->
                        retries.add(attempt)
                        assertEquals(expectedAttempts - 1, maximum)
                    },
                ) {
                    attempts++
                    throw error
                }
            }.exceptionOrNull()
            assertSame(error, failure)
            assertEquals(expectedAttempts, attempts)
            assertEquals((1 until expectedAttempts).toList(), retries)
        }
    }

    @Test
    fun streamRetryBudgetCountsOnlyAdditionalAttempts() = runTest {
        val error = IOException("connection reset")
        for ((maxRetries, expectedAttempts) in listOf(0 to 1, 100 to 101, 101 to 101)) {
            var attempts = 0
            val retries = mutableListOf<Int>()
            val failure = runCatching {
                streamWithStaircaseRetry(
                    retryOptions = options.copy(maxRetries = maxRetries),
                    attemptOnce = {
                        attempts++
                        throw error
                    },
                    onRetry = { attempt, maximum, _ ->
                        retries.add(attempt)
                        assertEquals(expectedAttempts - 1, maximum)
                    },
                )
            }.exceptionOrNull()
            assertSame(error, failure)
            assertEquals(expectedAttempts, attempts)
            assertEquals((1 until expectedAttempts).toList(), retries)
        }
    }

    @Test
    fun lastAllowedAdditionalAttemptCanSucceed() = runTest {
        val maximum = options.copy(maxRetries = 100)
        var attempts = 0
        val result = retryStaircase(retryOptions = maximum) {
            attempts++
            if (attempts <= 100) throw IOException("connection reset")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(101, attempts)

        attempts = 0
        streamWithStaircaseRetry(
            retryOptions = maximum,
            attemptOnce = { received ->
                attempts++
                if (attempts <= 100) throw IOException("connection reset")
                received()
            },
        )
        assertEquals(101, attempts)
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
