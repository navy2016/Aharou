package com.aharou.feature.settings.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.agent.domain.provider.buildReasoningPreview
import com.aharou.feature.agent.presentation.component.ModelReasoningEffortSheet
import com.aharou.feature.settings.data.local.CustomRequestStore
import com.aharou.feature.settings.data.local.ReasoningConfigStore
import com.aharou.feature.settings.data.repository.ModelServiceTierRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ServiceTier
import dagger.hilt.android.EntryPointAccessors

@Composable
internal fun ModelReasoningSheet(
    provider: AIProviderConfig,
    model: String,
    effort: ReasoningEffort,
    metadata: ModelMetadata?,
    enabled: Boolean,
    onChange: (ReasoningEffort) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resolvedMetadata = rememberReasoningMetadata(provider.id, provider.type, model)
    val store = remember { ReasoningConfigStore(context.applicationContext) }
    val requests = remember { CustomRequestStore(context.applicationContext) }
    val serviceTiers = remember { ModelServiceTierRepository(context.applicationContext) }
    var serviceTier by remember(provider.id, model) {
        mutableStateOf(ServiceTier.fromName(serviceTiers.get(provider.id, model)) ?: ServiceTier.FOLLOW)
    }
    var config by remember(provider.id, model) { mutableStateOf(store.getModel(provider.id, model)) }
    var editing by remember(provider.id, model) { mutableStateOf(false) }
    val global = remember(provider.id, model, editing) { requests.getGlobal() }
    val providerRequest = remember(provider.id, model, editing) { requests.getProvider(provider.id) }
    val modelRequest = remember(provider.id, model, editing) { requests.getModel(provider.id, model) }
    if (editing && enabled && resolvedMetadata != null) {
        ReasoningConfigSheet(
            model = model,
            initial = config,
            effort = effort,
            preview = { candidate, selected ->
                buildReasoningPreview(provider, model, selected, candidate, resolvedMetadata, global, providerRequest, modelRequest)
            },
            onSave = {
                if (enabled) {
                    store.setModel(provider.id, model, it)
                    config = it
                    editing = false
                }
            },
            onDismiss = { editing = false },
        )
    } else {
        ModelReasoningEffortSheet(
            model = model,
            effort = effort,
            modelMetadata = metadata,
            enabled = enabled,
            preview = resolvedMetadata?.let { buildReasoningPreview(provider, model, effort, config, it, global, providerRequest, modelRequest) },
            onOpenConfig = { if (enabled && resolvedMetadata != null) editing = true },
            onChange = { if (enabled) onChange(it) },
            onDismiss = onDismiss,
            serviceTier = serviceTier,
            onServiceTierChange = if (provider.type == ProviderType.OPENAI) {
                { tier ->
                    if (enabled) {
                        serviceTiers.set(provider.id, model, tier.name)
                        serviceTier = tier
                        onDismiss()
                    }
                }
            } else null,
        )
    }
}

@Composable
internal fun rememberReasoningMetadata(providerId: String, type: ProviderType, model: String): ModelMetadata? {
    val context = LocalContext.current
    val service = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, ModelTestEntryPoint::class.java).modelMetadataService()
    }
    var metadata by remember(providerId, type, model) { mutableStateOf<ModelMetadata?>(null) }
    LaunchedEffect(providerId, type, model) {
        if (model.isNotBlank()) metadata = service.resolve(providerId, type, model)
    }
    return metadata
}
