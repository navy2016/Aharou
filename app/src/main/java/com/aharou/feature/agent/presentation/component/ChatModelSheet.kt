package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.data.local.CustomModelMetadataStore
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.modelMetadataKey
import com.aharou.feature.settings.presentation.component.ModelLogoIcon
import com.aharou.feature.settings.presentation.component.ModelSelectionSheet
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Zap

@Composable
internal fun ModelIconButton(
    provider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    onSelectModel: (String, String) -> Unit,
    reasoningEffort: ReasoningEffort,
    onReasoningEffortChange: (String, String, ReasoningEffort) -> Unit,
    currentReasoningEffortEnabled: Boolean,
    modifier: Modifier = Modifier,
    forceOpenSheet: Boolean = false,
    onSelectSuccessInOnboarding: (() -> Unit)? = null,
    onSheetDismiss: (() -> Unit)? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    val effectiveShowSheet = showSheet || forceOpenSheet
    val context = LocalContext.current
    val customIconStore = remember { CustomModelMetadataStore(context.applicationContext) }
    var customIcon by remember { mutableStateOf("") }
    val effectiveModel = provider?.effectiveModel.orEmpty()
    LaunchedEffect(provider?.id, effectiveModel) {
        customIcon = customIconStore.all()[modelMetadataKey(provider?.id.orEmpty(), effectiveModel)]
            ?.customIcon?.takeIf { it.isNotBlank() }
            ?: modelMetadata[modelMetadataKey(provider?.id.orEmpty(), effectiveModel)]?.customIcon.orEmpty()
    }
    IconButton(onClick = { showSheet = true }, modifier = modifier.size(36.dp)) {
        ModelLogoIcon(modelName = effectiveModel, customIcon = customIcon, size = 20.dp)
    }

    if (effectiveShowSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.common_model),
            noModelsText = stringResource(R.string.chat_no_models_hint),
            providers = providers,
            currentProviderId = provider?.id.orEmpty(),
            currentModel = effectiveModel,
            modelMetadata = modelMetadata,
            currentReasoningEffort = reasoningEffort,
            currentReasoningEffortEnabled = currentReasoningEffortEnabled,
            onReasoningEffortChange = onReasoningEffortChange,
            onSelect = { pId, model ->
                onSelectModel(pId, model)
                showSheet = false
                onSelectSuccessInOnboarding?.invoke()
            },
            onClear = null,
            onDismiss = {
                showSheet = false
                onSheetDismiss?.invoke()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelReasoningEffortSheet(
    model: String,
    effort: ReasoningEffort,
    modelMetadata: ModelMetadata?,
    onChange: (ReasoningEffort) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.chat_reasoning_effort),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = model,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
            val declaredEfforts = modelMetadata?.reasoningEffortOptions
            if (declaredEfforts.isNullOrEmpty()) {
                when {
                    modelMetadata?.reasoningBudgetTokens == true -> EffortHint(
                        stringResource(R.string.model_reasoning_budget_tokens),
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    modelMetadata?.reasoningSupportsToggle == true -> EffortHint(
                        stringResource(R.string.model_reasoning_toggle_only),
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val toggleExpressesNone = modelMetadata?.reasoningSupportsToggle == true && effort == ReasoningEffort.NONE
            val reasoningCapable = modelMetadata?.supportsReasoning == true || !declaredEfforts.isNullOrEmpty()
            val effortValue = effort.apiValue
            if (reasoningCapable && effortValue != null && !toggleExpressesNone &&
                declaredEfforts?.contains(effortValue) != true
            ) {
                EffortHint(stringResource(R.string.model_reasoning_effort_unverified), MaterialTheme.colorScheme.tertiary)
            }
            ReasoningEffort.entries.forEach { value ->
                val selected = value == effort
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm))
                        .clickable(enabled = enabled) { onChange(value) }.padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        FeatherIcons.Zap, contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        reasoningEffortLabel(value),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selected) {
                        Icon(FeatherIcons.Check, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EffortHint(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color,
        modifier = Modifier.padding(bottom = Spacing.sm))
}

@Composable
internal fun reasoningEffortLabel(effort: ReasoningEffort): String {
    val name = stringResource(effort.labelRes())
    val apiValue = effort.apiValue ?: return name
    return stringResource(R.string.common_label_with_api, name, apiValue)
}

internal fun ReasoningEffort.labelRes(): Int = when (this) {
    ReasoningEffort.DEFAULT -> R.string.chat_reasoning_effort_default
    ReasoningEffort.NONE -> R.string.chat_reasoning_effort_none
    ReasoningEffort.LOW -> R.string.chat_reasoning_effort_low
    ReasoningEffort.MEDIUM -> R.string.chat_reasoning_effort_medium
    ReasoningEffort.HIGH -> R.string.chat_reasoning_effort_high
    ReasoningEffort.XHIGH -> R.string.chat_reasoning_effort_xhigh
    ReasoningEffort.MAX -> R.string.chat_reasoning_effort_max
}
