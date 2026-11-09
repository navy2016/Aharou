package com.aharou.feature.agent.domain.provider

import com.aharou.core.config.CONFIG_MASK_TEXT
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.agent.domain.middleware.RequestMiddleware
import com.aharou.feature.agent.domain.middleware.resolveProviderParameters
import com.aharou.feature.settings.data.local.CustomRequestOverride
import com.aharou.feature.settings.data.local.mergeCustomRequestBody
import com.aharou.feature.settings.data.local.mergeCustomRequestOverrides
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

fun reasoningTransport(type: ProviderType, useResponseApi: Boolean): ReasoningTransport = when (type) {
    ProviderType.OPENAI -> if (useResponseApi) ReasoningTransport.OPENAI_RESPONSES else ReasoningTransport.OPENAI_CHAT
    ProviderType.ANTHROPIC -> ReasoningTransport.ANTHROPIC
    ProviderType.GEMINI -> if (useResponseApi) ReasoningTransport.GEMINI_INTERACTIONS else ReasoningTransport.GEMINI_CONTENT
}

fun buildReasoningPreview(
    provider: AIProviderConfig,
    model: String,
    effort: ReasoningEffort,
    config: ReasoningConfig,
    metadata: ModelMetadata?,
    global: CustomRequestOverride,
    providerRequest: CustomRequestOverride,
    modelRequest: CustomRequestOverride,
): ReasoningPreview {
    val transport = reasoningTransport(provider.type, provider.useResponseApi)
    val middleware = RequestMiddleware.parseConfig(provider.requestMiddleware)?.takeIf { it.enabled }
    val actualModel = middleware?.modelMap?.get(model)?.takeIf { it.isNotBlank() } ?: model
    val limits = resolveProviderParameters(
        provider.requestMiddleware, provider.id, provider.type.name, actualModel,
        if (metadata?.supportsCustomTemperature == true) fixedTemperature(model) else null, metadata?.outputTokens
    )
    val resolution = ReasoningRequestResolver.resolve(actualModel, provider.baseUrl, transport, effort, config)
    val base = JsonObject()
    when (transport) {
        ReasoningTransport.ANTHROPIC -> {
            base.addProperty("max_tokens", limits.maxOutputTokens?.takeIf { it > 0 } ?: 16384)
            limits.temperature?.let { base.addProperty("temperature", it) }
        }
        ReasoningTransport.OPENAI_CHAT -> {
            limits.temperature?.let { base.addProperty("temperature", it) }
        }
        ReasoningTransport.GEMINI_CONTENT, ReasoningTransport.GEMINI_INTERACTIONS -> {
            limits.maxOutputTokens?.takeIf { it > 0 }?.let { limit ->
                val content = transport == ReasoningTransport.GEMINI_CONTENT
                base.add(if (content) "generationConfig" else "generation_config", JsonObject().apply {
                    addProperty(if (content) "maxOutputTokens" else "max_output_tokens", limit)
                })
            }
        }
        else -> Unit
    }
    val generated = ReasoningRequestResolver.apply(base, resolution, transport)
    val custom = mergeCustomRequestOverrides(global, providerRequest, modelRequest).body
    val body = custom?.let { mergeCustomRequestBody(generated, it) } ?: generated
    val paths = linkedSetOf(
        "reasoning_effort", "reasoning", "thinking", "output_config.effort", "enable_thinking", "thinking_budget",
        "generationConfig.thinkingConfig", "generation_config.thinking_level", "generation_config.thinking_summaries",
        "temperature", "max_tokens", "generationConfig.maxOutputTokens", "generation_config.max_output_tokens",
    )
    config.patches[effort]?.entrySet()?.forEach { (key, _) ->
        if (key !in setOf("generationConfig", "generation_config", "output_config", "messages", "input", "contents")) paths += key
    }
    val sources = linkedMapOf<String, ReasoningOverrideScope>()
    listOf(
        ReasoningOverrideScope.GLOBAL to global,
        ReasoningOverrideScope.PROVIDER to providerRequest,
        ReasoningOverrideScope.MODEL to modelRequest,
    ).forEach { (scope, layer) ->
        layer.body?.let { patch ->
            paths.filter { hasPathOrAncestor(patch, it) }.forEach { sources[it] = scope }
        }
    }
    sources.keys.removeAll { custom == null || !hasPathOrAncestor(custom, it) }
    val visible = JsonObject()
    paths.forEach { path -> valueAt(body, path)?.let { visible.add(path, maskPreviewValue(it, path)) } }
    val warnings = (resolution.warnings + ReasoningRequestResolver.diagnostics(body)).toMutableSet()
    if (effort == ReasoningEffort.NONE) {
        if (ReasoningRequestResolver.hasExplicitOffParameters(body)) warnings.remove(ReasoningWarning.CANNOT_DISABLE)
        else warnings += ReasoningWarning.CANNOT_DISABLE
    }
    val budget = listOf("thinking.budget_tokens", "generationConfig.thinkingConfig.thinkingBudget", "thinking_budget")
        .mapNotNull { valueAt(body, it)?.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber }?.asDouble }
        .firstOrNull()
    if (budget != null && (metadata?.reasoningBudgetTokensMin?.let { budget < it } == true ||
            metadata?.reasoningBudgetTokensMax?.let { budget > it } == true)) warnings += ReasoningWarning.BUDGET_OUTSIDE_MODEL_RANGE
    val gson = GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create()
    return ReasoningPreview(
        selectedEffort = effort,
        dialect = resolution.dialect,
        parameters = gson.toJson(visible),
        overrides = ReasoningOverrideScope.entries.mapNotNull { scope ->
            sources.filterValues { it == scope }.keys.toList().takeIf { it.isNotEmpty() }?.let { ReasoningOverride(scope, it) }
        },
        warnings = warnings,
    )
}

private fun valueAt(obj: JsonObject, path: String): JsonElement? {
    var current: JsonElement = obj
    for (part in path.split('.')) {
        if (!current.isJsonObject) return current
        current = current.asJsonObject.get(part) ?: return null
    }
    return current
}

private fun hasPathOrAncestor(obj: JsonObject, path: String): Boolean {
    var current: JsonElement = obj
    for (part in path.split('.')) {
        if (!current.isJsonObject) return true
        current = current.asJsonObject.get(part) ?: return false
    }
    return true
}

private val previewLiterals = setOf(
    "none", "minimal", "low", "medium", "high", "xhigh", "max", "enabled", "disabled", "adaptive", "summarized", "auto",
)

private fun maskPreviewValue(value: JsonElement, path: String): JsonElement = when {
    value.isJsonNull -> value
    value.isJsonPrimitive -> {
        val primitive = value.asJsonPrimitive
        val knownPath = path.substringAfterLast('.') in setOf(
            "reasoning_effort", "effort", "type", "display", "thinkingLevel", "thinking_level", "thinking_summaries",
            "budget_tokens", "thinkingBudget", "thinking_budget", "includeThoughts", "enable_thinking", "max_tokens",
            "maxOutputTokens", "max_output_tokens", "temperature", "enabled",
        )
        if (knownPath && (primitive.isBoolean || primitive.isNumber || primitive.asString.lowercase() in previewLiterals)) value
        else JsonPrimitive(CONFIG_MASK_TEXT)
    }
    value.isJsonObject -> JsonObject().apply {
        value.asJsonObject.entrySet().forEach { (key, child) -> add(key, maskPreviewValue(child, "$path.$key")) }
    }
    else -> JsonPrimitive(CONFIG_MASK_TEXT)
}
