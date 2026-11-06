package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.domain.steward.StewardSettings
import com.aharou.feature.settings.data.local.CustomRequestStore
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRequestSettings @Inject constructor(
    private val generalSettings: GeneralSettingsRepository,
    private val stewardSettings: StewardSettings,
    private val customRequestStore: CustomRequestStore,
) {
    suspend fun apply(provider: AIProvider, config: AIProviderConfig) {
        val custom = customRequestStore.resolve(config.id, config.effectiveModel, config.customHeaders)
        provider.customHeaders = custom.headers
        provider.customBody = custom.body
        provider.firstByteTimeoutMs = generalSettings.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettings.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettings.maxNetworkRetries()
        provider.autoRetryOptions = stewardSettings.autoRetryOptions
    }
}
