package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.provider.ReasoningOverrideScope
import com.aharou.feature.agent.domain.provider.ReasoningPreview
import com.aharou.feature.agent.domain.provider.ReasoningWarning
import com.aharou.feature.agent.presentation.component.reasoningEffortLabel
import com.aharou.feature.settings.domain.model.ReasoningDialect

@Composable
internal fun ReasoningPreviewCard(preview: ReasoningPreview, modifier: Modifier = Modifier) {
    val parametersMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.3f
    SettingsGroup(modifier = modifier) {
        Column(
            Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.reasoning_preview_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.reasoning_preview_selected_effort, reasoningEffortLabel(preview.selectedEffort)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.reasoning_preview_dialect, reasoningDialectLabel(preview.dialect)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.reasoning_preview_parameters_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.semanticColors.mutedSurface,
                shape = RoundedCornerShape(Radius.sm),
            ) {
                SelectionContainer {
                    Text(
                        text = preview.parameters,
                        modifier = Modifier.fillMaxWidth().heightIn(max = parametersMaxHeight)
                            .verticalScroll(rememberScrollState()).padding(Spacing.md),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text(stringResource(R.string.reasoning_preview_sources), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(
                    if (preview.overrides.isEmpty()) R.string.reasoning_preview_no_overrides
                    else R.string.reasoning_preview_overrides_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            preview.overrides.forEach { override ->
                Text(
                    stringResource(when (override.scope) {
                        ReasoningOverrideScope.GLOBAL -> R.string.reasoning_preview_source_global
                        ReasoningOverrideScope.PROVIDER -> R.string.reasoning_preview_source_provider
                        ReasoningOverrideScope.MODEL -> R.string.reasoning_preview_source_model
                    }),
                    style = MaterialTheme.typography.labelMedium,
                )
                SelectionContainer {
                    Text(
                        text = override.paths.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (preview.warnings.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.semanticColors.warningContainer,
                    contentColor = MaterialTheme.semanticColors.onWarningContainer,
                    shape = RoundedCornerShape(Radius.sm),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(stringResource(R.string.reasoning_preview_warnings), style = MaterialTheme.typography.labelLarge)
                        ReasoningWarning.entries.filter { it in preview.warnings }.forEach { warning ->
                            Text(
                                stringResource(when (warning) {
                                    ReasoningWarning.CANNOT_DISABLE -> R.string.reasoning_warning_cannot_disable
                                    ReasoningWarning.LEVEL_MAPPED -> R.string.reasoning_warning_level_mapped
                                    ReasoningWarning.BUDGET_EXCEEDS_OUTPUT -> R.string.reasoning_warning_budget_exceeds_output
                                    ReasoningWarning.INCOMPATIBLE_DIALECT -> R.string.reasoning_warning_incompatible_dialect
                                    ReasoningWarning.CONFLICTING_PARAMETERS -> R.string.reasoning_warning_conflicting_parameters
                                    ReasoningWarning.BUDGET_ADJUSTED -> R.string.reasoning_warning_budget_adjusted
                                    ReasoningWarning.BUDGET_OUTSIDE_MODEL_RANGE -> R.string.reasoning_warning_budget_outside_model_range
                                }),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun reasoningDialectLabel(dialect: ReasoningDialect): String = stringResource(when (dialect) {
    ReasoningDialect.AUTO -> R.string.reasoning_dialect_auto
    ReasoningDialect.OPENAI_EFFORT -> R.string.reasoning_dialect_openai_effort
    ReasoningDialect.ANTHROPIC_BUDGET -> R.string.reasoning_dialect_anthropic_budget
    ReasoningDialect.ANTHROPIC_ADAPTIVE -> R.string.reasoning_dialect_anthropic_adaptive
    ReasoningDialect.GEMINI_BUDGET -> R.string.reasoning_dialect_gemini_budget
    ReasoningDialect.GEMINI_LEVEL -> R.string.reasoning_dialect_gemini_level
    ReasoningDialect.THINKING_TYPE -> R.string.reasoning_dialect_thinking_type
    ReasoningDialect.ENABLE_THINKING -> R.string.reasoning_dialect_enable_thinking
    ReasoningDialect.CUSTOM -> R.string.reasoning_dialect_custom
})
