package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.provider.AutoRetryOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoRetrySettingsSheet(
    options: AutoRetryOptions,
    onSave: (AutoRetryOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember { mutableStateOf(options.enabled) }
    var maxRetries by remember { mutableStateOf(options.maxRetries.toString()) }
    var initialDelay by remember { mutableStateOf(options.initialDelayMs.toString()) }
    var multiplier by remember { mutableStateOf(options.multiplier.toString()) }
    var maxDelay by remember { mutableStateOf(options.maxDelayMs.toString()) }
    var jitter by remember { mutableStateOf(options.jitter) }
    var retryNetwork by remember { mutableStateOf(options.retryOnNetworkError) }
    var statusCodes by remember { mutableStateOf(AutoRetryOptions.formatStatusCodes(options.retryStatusCodes)) }
    var retryKeywords by remember { mutableStateOf(AutoRetryOptions.formatKeywords(options.retryKeywords)) }
    var stopKeywords by remember { mutableStateOf(AutoRetryOptions.formatKeywords(options.stopKeywords)) }
    val retries = maxRetries.toIntOrNull()
    val initial = initialDelay.toLongOrNull()
    val factor = multiplier.toDoubleOrNull()
    val maximum = maxDelay.toLongOrNull()
    val codes = statusCodes.split(',', '，', '\n').map(String::trim).filter(String::isNotEmpty)
    val valid = retries != null && retries in AutoRetryOptions.MIN_MAX_RETRIES..AutoRetryOptions.MAX_MAX_RETRIES &&
        initial != null && initial in 0..Int.MAX_VALUE.toLong() &&
        factor != null && factor.isFinite() && factor > 0 && factor <= AutoRetryOptions.MAX_MULTIPLIER &&
        maximum != null && maximum in 0..Int.MAX_VALUE.toLong() &&
        codes.all { value -> value.toIntOrNull()?.let { it in 100..599 } == true }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.retry_enhanced_title), style = MaterialTheme.typography.titleMedium)
            RetrySwitchRow(stringResource(R.string.retry_enhanced_enable), enabled) { enabled = it }
            AppTextField(
                value = maxRetries, onValueChange = { maxRetries = it },
                label = stringResource(R.string.retry_max_retries),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = retries == null || retries !in AutoRetryOptions.MIN_MAX_RETRIES..AutoRetryOptions.MAX_MAX_RETRIES,
            )
            AppTextField(
                value = initialDelay, onValueChange = { initialDelay = it },
                label = stringResource(R.string.retry_initial_delay),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = initial == null || initial !in 0..Int.MAX_VALUE.toLong(),
            )
            AppTextField(
                value = multiplier, onValueChange = { multiplier = it },
                label = stringResource(R.string.retry_multiplier),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = factor == null || !factor.isFinite() || factor <= 0 || factor > AutoRetryOptions.MAX_MULTIPLIER,
            )
            AppTextField(
                value = maxDelay, onValueChange = { maxDelay = it },
                label = stringResource(R.string.retry_max_delay),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = maximum == null || maximum !in 0..Int.MAX_VALUE.toLong(),
            )
            RetrySwitchRow(stringResource(R.string.retry_jitter), jitter) { jitter = it }
            RetrySwitchRow(stringResource(R.string.retry_network), retryNetwork) { retryNetwork = it }
            AppTextField(
                value = statusCodes, onValueChange = { statusCodes = it },
                label = stringResource(R.string.retry_status_codes),
                isError = codes.any { value -> value.toIntOrNull()?.let { it in 100..599 } != true },
            )
            AppTextField(
                value = retryKeywords, onValueChange = { retryKeywords = it },
                label = stringResource(R.string.retry_keywords), singleLine = false, minLines = 2,
            )
            AppTextField(
                value = stopKeywords, onValueChange = { stopKeywords = it },
                label = stringResource(R.string.retry_stop_keywords), singleLine = false, minLines = 2,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    statusCodes = AutoRetryOptions.formatStatusCodes(AutoRetryOptions.DEFAULT_RETRY_STATUS_CODES)
                    retryKeywords = AutoRetryOptions.formatKeywords(AutoRetryOptions.DEFAULT_RETRY_KEYWORDS)
                    stopKeywords = AutoRetryOptions.formatKeywords(AutoRetryOptions.DEFAULT_STOP_KEYWORDS)
                }) { Text(stringResource(R.string.common_reset)) }
                Spacer(Modifier.weight(1f))
                Button(enabled = valid, onClick = {
                    onSave(AutoRetryOptions(
                        enabled = enabled,
                        maxRetries = retries!!,
                        initialDelayMs = initial!!,
                        multiplier = factor!!,
                        maxDelayMs = maximum!!,
                        jitter = jitter,
                        retryOnNetworkError = retryNetwork,
                        retryStatusCodes = AutoRetryOptions.parseStatusCodes(statusCodes),
                        retryKeywords = AutoRetryOptions.parseKeywords(retryKeywords, emptyList()),
                        stopKeywords = AutoRetryOptions.parseKeywords(stopKeywords, emptyList()),
                    ))
                }) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}

@Composable
private fun RetrySwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        AppSwitch(checked = checked, onCheckedChange = onChange)
    }
}
