package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.settings.data.local.CustomRequestOverride
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Headers

private fun parseObject(value: String): JsonObject? =
    if (value.isBlank()) JsonObject()
    else runCatching { JsonParser.parseString(value).asJsonObject }.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomRequestSheet(
    initial: CustomRequestOverride,
    onSave: (CustomRequestOverride) -> Unit,
    onDismiss: () -> Unit,
) {
    val gson = remember { GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create() }
    var headers by remember { mutableStateOf(if (initial.headers.isEmpty()) "" else gson.toJson(initial.headers)) }
    var body by remember { mutableStateOf(initial.body?.let { gson.toJson(it) }.orEmpty()) }
    val headerObject = parseObject(headers)
    val bodyObject = parseObject(body)
    val headerMap = headerObject?.let { obj ->
        runCatching {
            val builder = Headers.Builder()
            obj.entrySet().associate { (name, value) ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                builder.add(name, value.asString)
                name to value.asString
            }
        }.getOrNull()
    }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.custom_request_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.custom_request_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.reasoning_custom_request_override_hint), style = MaterialTheme.typography.bodySmall)
            AppTextField(
                value = headers, onValueChange = { headers = it },
                label = stringResource(R.string.custom_request_headers),
                placeholder = "{\"X-Example\": \"value\"}",
                singleLine = false, minLines = 3, isError = headerMap == null,
                supportingText = if (headerMap == null) {
                    { Text(stringResource(R.string.custom_request_headers_invalid)) }
                } else null,
            )
            AppTextField(
                value = body, onValueChange = { body = it },
                label = stringResource(R.string.custom_request_body),
                placeholder = "{\"temperature\": 0.7}",
                singleLine = false, minLines = 4, isError = bodyObject == null,
                supportingText = if (bodyObject == null) {
                    { Text(stringResource(R.string.custom_request_body_invalid)) }
                } else null,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = headerMap != null && bodyObject != null,
                onClick = { onSave(CustomRequestOverride(headerMap!!, bodyObject?.takeIf { it.size() > 0 })) },
            ) { Text(stringResource(R.string.common_save)) }
        }
    }
}
