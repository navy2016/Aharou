package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.domain.steward.StewardSettings
import com.aharou.feature.settings.data.local.CustomRequestOverride
import com.aharou.feature.settings.data.local.CustomRequestStore
import com.aharou.feature.settings.data.local.ReasoningConfigStore
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.data.repository.ModelServiceTierRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.google.gson.JsonObject
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProviderRequestSettingsTest {
    @Test
    fun appliesModelTierAlongsideCustomRequestsAndRetryThenClearsFollowTier() = runTest {
        val general = mockk<GeneralSettingsRepository>()
        coEvery { general.firstByteTimeoutMs() } returns 12_000L
        coEvery { general.streamIdleTimeoutMs() } returns 15_000L
        coEvery { general.maxNetworkRetries() } returns 6
        val retry = AutoRetryOptions(enabled = true, maxRetries = 100)
        val steward = mockk<StewardSettings>()
        every { steward.autoRetryOptions } returns retry
        val body = JsonObject().apply { addProperty("service_tier", "fixed-value") }
        val custom = mockk<CustomRequestStore>()
        every { custom.resolve("provider", "model", emptyMap()) } returns CustomRequestOverride(
            headers = mapOf("X-Test" to "value"), body = body
        )
        val reasoning = mockk<ReasoningConfigStore>()
        val reasoningConfig = ReasoningConfig()
        every { reasoning.getModel("provider", "model") } returns reasoningConfig
        val tiers = mockk<ModelServiceTierRepository>()
        every { tiers.apiValueFor("provider", "model") } returns "fast"
        val settings = ProviderRequestSettings(general, steward, custom, reasoning, tiers)
        val provider = mockk<AIProvider>(relaxed = true)
        val config = AIProviderConfig(id = "provider", name = "Provider", type = ProviderType.OPENAI,
            apiKey = "", baseUrl = "", defaultModel = "model")

        settings.apply(provider, config)

        verify { provider.serviceTier = "fast" }
        verify { provider.customBody = body }
        verify { provider.customHeaders = mapOf("X-Test" to "value") }
        verify { provider.reasoningConfig = reasoningConfig }
        verify { provider.autoRetryOptions = retry }
        verify { provider.firstByteTimeoutMs = 12_000L }
        verify { provider.streamIdleTimeoutMs = 15_000L }
        every { tiers.apiValueFor("provider", "model") } returns null

        settings.apply(provider, config)

        verify { provider.serviceTier = null }
    }
}
