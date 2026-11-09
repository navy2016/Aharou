package com.aharou.feature.settings.data.remote

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.data.local.CustomRequestOverride
import com.aharou.feature.settings.data.local.CustomRequestStore
import com.aharou.feature.settings.data.local.ReasoningConfigStore
import com.aharou.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aharou.feature.settings.data.repository.ModelServiceTierRepository
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ModelReasoningRequestTest {
    @Test
    fun modelTestUsesMappedModelAndLogicalModelConfiguration() = runTest {
        val captured = mutableListOf<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            captured += JsonParser.parseString(buffer.readUtf8()).asJsonObject
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"type":"message","role":"assistant","content":[],"stop_reason":"end_turn"}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val requests = mockk<CustomRequestStore>()
        val config = mockk<ReasoningConfigStore>()
        val effort = mockk<ModelReasoningEffortRepository>()
        every { requests.resolve("p", "alias", emptyMap()) } returns CustomRequestOverride()
        every { config.getModel("p", "alias") } returns ReasoningConfig()
        every { effort.get("p", "alias") } returns ReasoningEffort.HIGH.name
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tiers = mockk<ModelServiceTierRepository>()
        every { tiers.apiValueFor("p", "alias") } returns "fast"
        val service = ModelApiService(client, requests, config, effort, tiers, context)
        val middleware = """{"modelMap":{"alias":"claude-sonnet-4-6"}}"""

        assertTrue(service.testModel("https://example.com", "", ProviderType.ANTHROPIC, false, false,
            "alias", providerId = "p", requestMiddleware = middleware).success)
        assertEquals("claude-sonnet-4-6", captured.last()["model"].asString)
        assertEquals("adaptive", captured.last().getAsJsonObject("thinking")["type"].asString)
        assertEquals("high", captured.last().getAsJsonObject("output_config")["effort"].asString)
        assertFalse(captured.last().has("service_tier"))

        every { requests.resolve("p", "alias", emptyMap()) } returns CustomRequestOverride(
            body = JsonParser.parseString("""{"output_config":{"effort":"low"},"private_field":"hidden-test-value"}""").asJsonObject)
        val overridden = service.testModel("https://example.com", "", ProviderType.ANTHROPIC, false, false,
            "alias", providerId = "p", requestMiddleware = middleware)
        assertTrue(overridden.success)
        assertEquals("low", captured.last().getAsJsonObject("output_config")["effort"].asString)
        assertFalse(overridden.requestBody.contains("hidden-test-value"))
    }

    @Test
    fun fullUrlSelectsMatchingBodyReasoningAndResponseProtocol() = runTest {
        data class Case(
            val type: ProviderType,
            val responsesFlag: Boolean,
            val path: String,
            val model: String,
            val response: String,
            val protocol: ModelTestProtocol,
        )
        val cases = listOf(
            Case(ProviderType.OPENAI, true, "/v1/chat/completions", "gpt-5",
                """{"choices":[{"message":{"role":"assistant","content":"hi"}}]}""", ModelTestProtocol.CHAT),
            Case(ProviderType.OPENAI, false, "/v1/responses", "gpt-5",
                """{"object":"response","status":"completed","output":[]}""", ModelTestProtocol.RESPONSES),
            Case(ProviderType.ANTHROPIC, false, "/v1/messages", "claude-sonnet-4-6",
                """{"type":"message","role":"assistant","content":[],"stop_reason":"end_turn"}""", ModelTestProtocol.ANTHROPIC),
            Case(ProviderType.GEMINI, true, "/v1beta/models/gemini-3-pro:generateContent", "gemini-3-pro",
                """{"candidates":[{"finishReason":"STOP"}]}""", ModelTestProtocol.GEMINI),
            Case(ProviderType.GEMINI, false, "/v1beta/interactions", "gemini-3-pro",
                """{"id":"fixture","status":"completed","outputs":[]}""", ModelTestProtocol.INTERACTIONS),
        )
        for (case in cases) {
            val captured = mutableListOf<JsonObject>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                captured += JsonParser.parseString(buffer.readUtf8()).asJsonObject
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(case.response.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val requests = mockk<CustomRequestStore>()
            val config = mockk<ReasoningConfigStore>()
            val effort = mockk<ModelReasoningEffortRepository>()
            val tiers = mockk<ModelServiceTierRepository>()
            every { requests.resolve("p", "alias", emptyMap()) } returns CustomRequestOverride()
            every { config.getModel("p", "alias") } returns ReasoningConfig()
            every { tiers.apiValueFor("p", "alias") } returns "fast"
            val service = ModelApiService(client, requests, config, effort, tiers,
                ApplicationProvider.getApplicationContext())
            val middleware = """{"modelMap":{"alias":"${case.model}"}}"""
            for (selected in listOf(ReasoningEffort.XHIGH, ReasoningEffort.MAX)) {
                every { effort.get("p", "alias") } returns selected.name
                val result = service.testModel("https://example.invalid${case.path}", "", case.type,
                    true, case.responsesFlag, "alias", providerId = "p", requestMiddleware = middleware)
                assertTrue("${case.protocol}: ${result.message}", result.success)
                assertEquals("https://example.invalid${case.path}", result.requestUrl)
                val body = captured.last()
                when (case.protocol) {
                    ModelTestProtocol.CHAT -> {
                        assertTrue(body.has("messages"))
                        assertFalse(body.has("input"))
                        assertFalse(body.has("reasoning"))
                        assertEquals(selected.apiValue, body["reasoning_effort"].asString)
                    }
                    ModelTestProtocol.RESPONSES -> {
                        assertTrue(body.has("input"))
                        assertFalse(body.has("messages"))
                        assertFalse(body.has("reasoning_effort"))
                        assertEquals(selected.apiValue, body.getAsJsonObject("reasoning")["effort"].asString)
                    }
                    ModelTestProtocol.ANTHROPIC -> {
                        assertTrue(body.has("messages"))
                        assertEquals("adaptive", body.getAsJsonObject("thinking")["type"].asString)
                        assertEquals(selected.apiValue, body.getAsJsonObject("output_config")["effort"].asString)
                    }
                    ModelTestProtocol.GEMINI -> {
                        assertTrue(body.has("contents"))
                        assertFalse(body.has("generation_config"))
                        assertEquals(selected.apiValue,
                            body.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig")["thinkingLevel"].asString)
                    }
                    ModelTestProtocol.INTERACTIONS -> {
                        assertTrue(body.has("input"))
                        assertFalse(body.has("generationConfig"))
                        assertEquals(selected.apiValue, body.getAsJsonObject("generation_config")["thinking_level"].asString)
                    }
                }
                if (case.type == ProviderType.OPENAI) assertEquals("fast", body["service_tier"].asString)
                else assertFalse(body.has("service_tier"))
                if (case.protocol != ModelTestProtocol.GEMINI) assertEquals(case.model, body["model"].asString)
            }
            verify(exactly = 2) { tiers.apiValueFor("p", "alias") }
        }
    }

    @Test
    fun openAiServiceTierDefaultsAndFixedBodyOverrideReasoningPatch() = runTest {
        for (responses in listOf(false, true)) {
            val captured = mutableListOf<JsonObject>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                captured += JsonParser.parseString(buffer.readUtf8()).asJsonObject
                val body = if (responses) """{"object":"response","status":"completed","output":[]}"""
                    else """{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val requests = mockk<CustomRequestStore>()
            val config = mockk<ReasoningConfigStore>()
            val effort = mockk<ModelReasoningEffortRepository>()
            val tiers = mockk<ModelServiceTierRepository>()
            every { requests.resolve("p", "gpt-5", emptyMap()) } returns CustomRequestOverride()
            every { config.getModel("p", "gpt-5") } returns ReasoningConfig()
            every { effort.get("p", "gpt-5") } returns ReasoningEffort.XHIGH.name
            every { tiers.apiValueFor("p", "gpt-5") } returns null
            val service = ModelApiService(client, requests, config, effort, tiers,
                ApplicationProvider.getApplicationContext())

            assertTrue(service.testModel("https://example.invalid", "", ProviderType.OPENAI,
                false, responses, "gpt-5", providerId = "p").success)
            assertFalse(captured.last().has("service_tier"))

            every { tiers.apiValueFor("p", "gpt-5") } returns "fast"
            every { config.getModel("p", "gpt-5") } returns ReasoningConfig(patches = mapOf(
                ReasoningEffort.XHIGH to JsonParser.parseString("""{"service_tier":"ultrafast"}""").asJsonObject))
            assertTrue(service.testModel("https://example.invalid", "", ProviderType.OPENAI,
                false, responses, "gpt-5", providerId = "p").success)
            assertEquals("ultrafast", captured.last()["service_tier"].asString)

            for (fixedTier in listOf("\"default\"", "null")) {
                every { requests.resolve("p", "gpt-5", emptyMap()) } returns CustomRequestOverride(
                    headers = mapOf("X-Private" to "fixture-header-value"),
                    body = JsonParser.parseString("""{"service_tier":$fixedTier,"private_field":"fixture-body-value"}""").asJsonObject)
                val result = service.testModel("https://example.invalid", "", ProviderType.OPENAI,
                    false, responses, "gpt-5", providerId = "p")
                assertTrue(result.success)
                assertEquals(JsonParser.parseString(fixedTier), captured.last()["service_tier"])
                assertEquals("fixture-body-value", captured.last()["private_field"].asString)
                assertFalse(result.requestBody.contains("fixture-body-value"))
                assertFalse(result.requestHeaders.values.any { it.contains("fixture-header-value") })
            }
        }
    }
}
