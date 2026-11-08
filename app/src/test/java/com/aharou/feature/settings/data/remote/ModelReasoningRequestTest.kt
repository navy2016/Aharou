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
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
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
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val requests = mockk<CustomRequestStore>()
        val config = mockk<ReasoningConfigStore>()
        val effort = mockk<ModelReasoningEffortRepository>()
        every { requests.resolve("p", "alias", emptyMap()) } returns CustomRequestOverride()
        every { config.getModel("p", "alias") } returns ReasoningConfig()
        every { effort.get("p", "alias") } returns ReasoningEffort.HIGH.name
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = ModelApiService(client, requests, config, effort, context)
        val middleware = """{"modelMap":{"alias":"claude-sonnet-4-6"}}"""

        assertTrue(service.testModel("https://example.com", "", ProviderType.ANTHROPIC, false, false,
            "alias", providerId = "p", requestMiddleware = middleware).success)
        assertEquals("claude-sonnet-4-6", captured.last()["model"].asString)
        assertEquals("adaptive", captured.last().getAsJsonObject("thinking")["type"].asString)
        assertEquals("high", captured.last().getAsJsonObject("output_config")["effort"].asString)

        every { requests.resolve("p", "alias", emptyMap()) } returns CustomRequestOverride(
            body = JsonParser.parseString("""{"output_config":{"effort":"low"},"private_field":"hidden-test-value"}""").asJsonObject)
        val overridden = service.testModel("https://example.com", "", ProviderType.ANTHROPIC, false, false,
            "alias", providerId = "p", requestMiddleware = middleware)
        assertTrue(overridden.success)
        assertEquals("low", captured.last().getAsJsonObject("output_config")["effort"].asString)
        assertFalse(overridden.requestBody.contains("hidden-test-value"))
    }
}
