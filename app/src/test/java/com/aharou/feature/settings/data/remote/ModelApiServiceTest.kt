package com.aharou.feature.settings.data.remote

import android.content.Context
import com.aharou.feature.settings.data.local.CustomRequestOverride
import com.aharou.feature.settings.data.local.CustomRequestStore
import com.aharou.feature.settings.data.local.ReasoningConfigStore
import com.aharou.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aharou.feature.settings.data.repository.ModelServiceTierRepository
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ReasoningConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelApiServiceTest {
    private fun service(body: String): ModelApiService {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } returns "diagnostic"
        every { context.getString(any(), *anyVararg()) } returns "diagnostic"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val requests = mockk<CustomRequestStore>()
        val config = mockk<ReasoningConfigStore>()
        val effort = mockk<ModelReasoningEffortRepository>()
        val serviceTier = mockk<ModelServiceTierRepository>()
        every { requests.resolve(any(), any(), any()) } returns CustomRequestOverride()
        every { config.getModel(any(), any()) } returns ReasoningConfig()
        every { effort.get(any(), any()) } returns null
        every { serviceTier.apiValueFor(any(), any()) } returns null
        return ModelApiService(client, requests, config, effort, serviceTier, context)
    }

    @Test fun successfulHttpWithApiErrorIsNotUsableModel() = runTest {
        val result = service("""{"error":{"message":"upstream unavailable"}}""").testModel(
            "https://example.invalid", "", ProviderType.OPENAI, false, false, "fixture")
        assertFalse(result.success)
        assertEquals(200, result.responseCode)
        assertEquals("""{"error":{"message":"upstream unavailable"}}""", result.errorDetail)
    }

    @Test fun successfulHttpWithHtmlOrEmptyJsonIsNotUsableModel() = runTest {
        listOf("<html>gateway login</html>", "{}", "[]", "").forEach { body ->
            val result = service(body).testModel(
                "https://example.invalid", "", ProviderType.OPENAI, false, false, "fixture")
            assertFalse(result.success)
            assertEquals(200, result.responseCode)
            assertEquals(body, result.errorDetail)
        }
    }

    @Test fun successfulHttpWithEmptyButValidMessageIsUsable() = runTest {
        val result = service("""{"choices":[{"message":{"role":"assistant","content":null},"finish_reason":"length"}]}""").testModel(
            "https://example.invalid", "", ProviderType.OPENAI, false, false, "fixture")
        assertTrue(result.success)
    }

    @Test fun eachProtocolAcceptsCompletedBusinessResponse() = runTest {
        val cases = listOf(
            Triple(ProviderType.OPENAI, false,
                """{"choices":[{"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}"""),
            Triple(ProviderType.OPENAI, true,
                """{"object":"response","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hi"}]}]}"""),
            Triple(ProviderType.ANTHROPIC, false,
                """{"type":"message","role":"assistant","content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn"}"""),
            Triple(ProviderType.GEMINI, false,
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]},"finishReason":"STOP"}]}"""),
            Triple(ProviderType.GEMINI, true,
                """{"id":"interaction-fixture","status":"completed","outputs":[{"type":"text","text":"hi"}]}""")
        )
        cases.forEach { (type, responses, body) ->
            val result = service(body).testModel(
                "https://example.invalid", "", type, false, responses, "fixture")
            assertTrue("$type responses=$responses: ${result.message}", result.success)
            assertEquals("", result.errorDetail)
            assertEquals(body, result.responseBody)
        }
    }

    @Test fun protocolBusinessFailuresAreNotSuccessfulHttpProbes() = runTest {
        val cases = listOf(
            Triple(ProviderType.OPENAI, true, """{"object":"response","status":"failed","output":[]}"""),
            Triple(ProviderType.ANTHROPIC, false, """{"type":"error","error":{"type":"overloaded_error"}}"""),
            Triple(ProviderType.GEMINI, false, """{"promptFeedback":{"blockReason":"SAFETY"}}"""),
            Triple(ProviderType.GEMINI, true, """{"id":"interaction-fixture","status":"failed","outputs":[]}""")
        )
        cases.forEach { (type, responses, body) ->
            val result = service(body).testModel(
                "https://example.invalid", "", type, false, responses, "fixture")
            assertFalse(result.success)
            assertEquals(200, result.responseCode)
            assertEquals(body, result.errorDetail)
        }
    }
}
