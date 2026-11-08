package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.agent.domain.provider.ReasoningPreview
import com.aharou.feature.agent.presentation.component.reasoningEffortLabel
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.aharou.feature.settings.domain.model.ReasoningDialect
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import compose.icons.FeatherIcons
import compose.icons.feathericons.RotateCcw
import java.io.StringReader

private const val MAX_REASONING_BUDGET = Int.MAX_VALUE - 4096

private data class ReasoningEffortInput(
    val value: String = "",
    val budget: String = "",
    val patch: String = "",
)

private data class ReasoningFormValidation(
    val config: ReasoningConfig?,
    val invalidBudgets: Set<ReasoningEffort>,
    val invalidPatches: Set<ReasoningEffort>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningConfigSheet(
    model: String,
    initial: ReasoningConfig,
    effort: ReasoningEffort,
    preview: (ReasoningConfig, ReasoningEffort) -> ReasoningPreview,
    onSave: (ReasoningConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val gson = remember { GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create() }
    var dialect by remember(model, initial) { mutableStateOf(initial.dialect) }
    var inputs by remember(model, initial) {
        mutableStateOf(
            ReasoningEffort.entries.filter { it != ReasoningEffort.DEFAULT }.associateWith { level ->
                ReasoningEffortInput(
                    value = initial.effortValues[level].orEmpty(),
                    budget = if (level == ReasoningEffort.NONE) "" else initial.budgets[level]?.toString().orEmpty(),
                    patch = initial.patches[level]?.let { gson.toJson(it) }.orEmpty(),
                )
            }
        )
    }
    var editingEffort by remember(model, initial) { mutableStateOf(effort) }
    val validation = remember(dialect, inputs) { validateReasoningForm(dialect, inputs, gson) }
    val invalidEfforts = validation.invalidBudgets + validation.invalidPatches
    val config = validation.config
    val result = config?.let { preview(it, editingEffort) }
    val focusManager = LocalFocusManager.current
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                stringResource(R.string.reasoning_config_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.reasoning_config_original_effort, reasoningEffortLabel(effort)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.reasoning_config_status),
                    subtitle = stringResource(
                        if (config?.isAutomatic == true) R.string.reasoning_config_automatic
                        else R.string.reasoning_config_manual
                    ),
                )
                SettingsDivider()
                Column(
                    Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    ReasoningDialectSelector(dialect = dialect, onChange = { dialect = it })
                    Text(
                        stringResource(R.string.reasoning_config_inherit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = {
                    focusManager.clearFocus()
                    dialect = ReasoningConfig().dialect
                    inputs = inputs.mapValues { ReasoningEffortInput() }
                },
                enabled = config?.isAutomatic != true,
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.reasoning_config_reset_all)) }

            SettingsGroupHeader(stringResource(R.string.reasoning_config_edit_effort))
            SettingsGroup {
                Column(
                    Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    ReasoningEffortSelector(
                        selected = editingEffort,
                        invalidEfforts = invalidEfforts,
                        onChange = {
                            focusManager.clearFocus()
                            editingEffort = it
                        },
                    )
                    Text(
                        stringResource(R.string.reasoning_config_preview_only_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (editingEffort == ReasoningEffort.DEFAULT) {
                        Text(
                            stringResource(R.string.reasoning_config_default_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        key(editingEffort) {
                            ReasoningEffortFields(
                                effort = editingEffort,
                                input = inputs.getValue(editingEffort),
                                invalidBudget = editingEffort in validation.invalidBudgets,
                                invalidPatch = editingEffort in validation.invalidPatches,
                                onChange = { inputs = inputs + (editingEffort to it) },
                            )
                        }
                    }
                }
            }
            if (result != null) {
                ReasoningPreviewCard(result)
            } else {
                Text(
                    stringResource(R.string.reasoning_config_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(
                        R.string.reasoning_config_invalid_efforts,
                        ReasoningEffort.entries.filter { it in invalidEfforts }
                            .map { reasoningEffortLabel(it) }.joinToString(" · "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    enabled = config != null,
                    onClick = {
                        val checked = validateReasoningForm(dialect, inputs, gson).config
                        if (checked != null) onSave(checked)
                    },
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningDialectSelector(dialect: ReasoningDialect, onChange: (ReasoningDialect) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        AppTextField(
            value = reasoningDialectLabel(dialect),
            onValueChange = {},
            readOnly = true,
            label = stringResource(R.string.reasoning_config_dialect),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReasoningDialect.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(reasoningDialectLabel(option)) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReasoningEffortSelector(
    selected: ReasoningEffort,
    invalidEfforts: Set<ReasoningEffort>,
    onChange: (ReasoningEffort) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ReasoningEffort.entries.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onChange(option) },
                label = { Text(reasoningEffortLabel(option)) },
                colors = if (option in invalidEfforts) FilterChipDefaults.filterChipColors(
                    labelColor = MaterialTheme.colorScheme.error,
                    selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                ) else FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun ReasoningEffortFields(
    effort: ReasoningEffort,
    input: ReasoningEffortInput,
    invalidBudget: Boolean,
    invalidPatch: Boolean,
    onChange: (ReasoningEffortInput) -> Unit,
) {
    val valueLabel = stringResource(R.string.reasoning_config_value)
    val budgetLabel = stringResource(R.string.reasoning_config_budget)
    val patchLabel = stringResource(R.string.reasoning_config_patch)
    val inheritPlaceholder = stringResource(R.string.reasoning_config_inherit)
    AppTextField(
        value = input.value,
        onValueChange = { onChange(input.copy(value = it)) },
        label = valueLabel,
        placeholder = inheritPlaceholder,
        supportingText = { Text(stringResource(R.string.reasoning_config_value_hint)) },
        trailingIcon = {
            ReasoningFieldReset(valueLabel, input.value.isNotEmpty()) { onChange(input.copy(value = "")) }
        },
    )
    if (effort != ReasoningEffort.NONE) {
        AppTextField(
            value = input.budget,
            onValueChange = { onChange(input.copy(budget = it)) },
            label = budgetLabel,
            placeholder = inheritPlaceholder,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = invalidBudget,
            supportingText = {
                Text(stringResource(R.string.reasoning_config_budget_range, MAX_REASONING_BUDGET))
            },
            trailingIcon = {
                ReasoningFieldReset(budgetLabel, input.budget.isNotEmpty()) { onChange(input.copy(budget = "")) }
            },
        )
    }
    AppTextField(
        value = input.patch,
        onValueChange = { onChange(input.copy(patch = it)) },
        label = patchLabel,
        placeholder = inheritPlaceholder,
        singleLine = false,
        minLines = 3,
        maxLines = 8,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        isError = invalidPatch,
        supportingText = {
            Text(stringResource(
                if (invalidPatch) R.string.reasoning_config_patch_invalid else R.string.reasoning_config_patch_hint
            ))
        },
        trailingIcon = {
            ReasoningFieldReset(patchLabel, input.patch.isNotEmpty()) { onChange(input.copy(patch = "")) }
        },
    )
}

@Composable
private fun ReasoningFieldReset(label: String, enabled: Boolean, onReset: () -> Unit) {
    IconButton(onClick = onReset, enabled = enabled) {
        Icon(
            FeatherIcons.RotateCcw,
            contentDescription = stringResource(R.string.reasoning_config_reset_field, label),
        )
    }
}

private fun validateReasoningForm(
    dialect: ReasoningDialect,
    inputs: Map<ReasoningEffort, ReasoningEffortInput>,
    gson: Gson,
): ReasoningFormValidation {
    val values = mutableMapOf<ReasoningEffort, String>()
    val budgets = mutableMapOf<ReasoningEffort, Int>()
    val patches = mutableMapOf<ReasoningEffort, JsonObject>()
    val invalidBudgets = mutableSetOf<ReasoningEffort>()
    val invalidPatches = mutableSetOf<ReasoningEffort>()
    inputs.forEach { (effort, input) ->
        if (input.value.isNotBlank()) values[effort] = input.value
        val budgetText = input.budget.trim()
        if (budgetText.isNotEmpty()) {
            val budget = budgetText.toIntOrNull()
            if (budget == null || budget !in 1..MAX_REASONING_BUDGET || budgetText.any { it !in '0'..'9' }) {
                invalidBudgets += effort
            } else {
                budgets[effort] = budget
            }
        }
        if (input.patch.isNotBlank()) {
            val patch = parseReasoningPatch(input.patch, gson)
            if (patch == null) invalidPatches += effort else patches[effort] = patch
        }
    }
    return ReasoningFormValidation(
        config = if (invalidBudgets.isEmpty() && invalidPatches.isEmpty()) {
            ReasoningConfig(dialect = dialect, effortValues = values, budgets = budgets, patches = patches)
        } else null,
        invalidBudgets = invalidBudgets,
        invalidPatches = invalidPatches,
    )
}

private fun parseReasoningPatch(text: String, gson: Gson): JsonObject? = runCatching {
    JsonReader(StringReader(text)).use { reader ->
        // JsonParser enables lenient parsing; reading the adapter directly preserves the reader setting.
        reader.isLenient = false
        val element = gson.getAdapter(JsonElement::class.java).read(reader)
        if (element.isJsonObject && reader.peek() == JsonToken.END_DOCUMENT) element.asJsonObject else null
    }
}.getOrNull()
