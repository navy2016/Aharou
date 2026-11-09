package com.aharou.feature.agent.domain.tool.config

import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.settings.data.remote.ModelApiService
import com.aharou.feature.settings.data.remote.ModelTestResult
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigModelTestActionTest {
    private val providers = mockk<AIProviderRepository>(relaxed = true)
    private val service = mockk<ModelApiService>()
    private val action = ConfigModelTestAction(providers, service)

    @Test fun missingProviderAndArgumentsDoNotSendRequests() = runTest {
        assertTrue(action.execute(emptyMap()) is ToolResult.Error)
        coEvery { providers.getProviderById("missing") } returns null
        assertTrue(action.execute(mapOf("provider_id" to JsonPrimitive("missing"), "model" to JsonPrimitive("model"))) is ToolResult.Error)
        coVerify(exactly = 0) { service.testModel(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun resultDoesNotReflectCredentialsOrRawResponseAndNeverSaves() = runTest {
        val provider = AIProviderConfig(
            id = "provider", name = "fixture", type = ProviderType.OPENAI, apiKey = "fixture-secret",
            baseUrl = "https://example.invalid", defaultModel = "model",
            customHeaders = mapOf("X-Test" to "fixture-header-secret"),
            requestMiddleware = """{"modelMap":{"model":"gpt-5"}}"""
        )
        coEvery { providers.getProviderById("provider") } returns provider
        coEvery { service.testModel(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns ModelTestResult(
            false, 12, "fixture-secret", requestHeaders = provider.customHeaders,
            requestBody = "fixture-body-secret", responseCode = 200, responseBody = "fixture-secret",
            errorDetail = "fixture-error-secret")
        val result = action.execute(mapOf("provider_id" to JsonPrimitive("provider"), "model" to JsonPrimitive("model")))
        assertTrue(result is ToolResult.Success)
        listOf("fixture-secret", "fixture-header-secret", "fixture-body-secret", "fixture-error-secret").forEach {
            assertFalse(result.toString().contains(it))
        }
        coVerify(exactly = 1) {
            service.testModel(provider.baseUrl, provider.firstUsableApiKey, provider.type,
                provider.useFullUrl, provider.useResponseApi, "model", provider.customHeaders,
                provider.id, provider.requestMiddleware)
        }
        coVerify(exactly = 0) { providers.saveProvider(any()) }
    }

    @Test fun modelTestUsesProviderConfigurationAndReportsFullUrlProtocol() = runTest {
        val provider = AIProviderConfig(
            id = "provider", name = "fixture", type = ProviderType.OPENAI, apiKey = "fixture-secret",
            baseUrl = "https://example.invalid/v1/responses", defaultModel = "alias",
            useFullUrl = true, useResponseApi = false,
            customHeaders = mapOf("X-Test" to "fixture-header-secret"),
            requestMiddleware = """{"modelMap":{"alias":"gpt-5"}}"""
        )
        coEvery { providers.getProviderById(provider.id) } returns provider
        coEvery {
            service.testModel(provider.baseUrl, provider.firstUsableApiKey, provider.type,
                true, false, "alias", provider.customHeaders, provider.id, provider.requestMiddleware)
        } returns ModelTestResult(true, 12, "ok", requestUrl = provider.baseUrl, responseCode = 200)

        val result = action.execute(mapOf("provider_id" to JsonPrimitive(" provider "), "model" to JsonPrimitive(" alias ")))

        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals("true", data["success"]!!.jsonPrimitive.content)
        assertEquals("provider", data["provider_id"]!!.jsonPrimitive.content)
        assertEquals("alias", data["model"]!!.jsonPrimitive.content)
        assertEquals("RESPONSES", data["protocol"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) {
            service.testModel(provider.baseUrl, provider.firstUsableApiKey, provider.type,
                true, false, "alias", provider.customHeaders, provider.id, provider.requestMiddleware)
        }
        coVerify(exactly = 0) { providers.saveProvider(any()) }
    }
}
