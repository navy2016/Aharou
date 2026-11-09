package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.data.local.mergeCustomRequestBody
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.aharou.feature.settings.domain.model.ReasoningDialect
import com.google.gson.JsonObject
import java.net.URI
import java.util.Locale

object ReasoningRequestResolver {
    fun resolve(
        model: String,
        baseUrl: String,
        transport: ReasoningTransport,
        effort: ReasoningEffort,
        config: ReasoningConfig = ReasoningConfig(),
    ): ReasoningResolution {
        val automatic = config.dialect == ReasoningDialect.AUTO
        val dialect = if (automatic) automaticDialect(model, baseUrl, transport, effort) else config.dialect
        val warnings = linkedSetOf<ReasoningWarning>()
        var parameters = JsonObject()
        if (effort == ReasoningEffort.DEFAULT) return ReasoningResolution(dialect, parameters)
        val off = effort == ReasoningEffort.NONE
        val explicitValue = config.effortValues[effort]
        val value = explicitValue ?: effort.apiValue!!
        val budget = config.budgets[effort] ?: when (effort) {
            ReasoningEffort.LOW -> 1024
            ReasoningEffort.MEDIUM -> 4096
            else -> 8192
        }
        val explicitOff = !automatic || explicitValue != null
        when (dialect) {
            ReasoningDialect.OPENAI_EFFORT -> {
                if (!off || explicitOff) {
                    if (transport == ReasoningTransport.OPENAI_RESPONSES) {
                        parameters.add("reasoning", JsonObject().apply {
                            addProperty("effort", value)
                            if (!model.contains("deepseek", true)) addProperty("summary", "auto")
                        })
                        if (!model.contains("deepseek", true)) {
                            parameters.add("include", com.google.gson.JsonArray().apply { add("reasoning.encrypted_content") })
                        }
                    } else parameters.addProperty("reasoning_effort", value)
                } else warnings += ReasoningWarning.CANNOT_DISABLE
            }
            ReasoningDialect.ANTHROPIC_BUDGET -> {
                if (off) {
                    if (explicitOff) parameters.add("thinking", type("disabled"))
                    else warnings += ReasoningWarning.CANNOT_DISABLE
                } else {
                    if (budget < 1024) warnings += ReasoningWarning.BUDGET_ADJUSTED
                    parameters.add("thinking", type("enabled").apply { addProperty("budget_tokens", budget.coerceAtLeast(1024)) })
                }
            }
            ReasoningDialect.ANTHROPIC_ADAPTIVE -> {
                if (off && automatic && !claudeThinkingProfile(model).canDisable) {
                    warnings += ReasoningWarning.CANNOT_DISABLE
                } else {
                    parameters.add("thinking", if (off) type("disabled") else type("adaptive").apply { addProperty("display", "summarized") })
                    if (!off) parameters.add("output_config", JsonObject().apply { addProperty("effort", value) })
                }
            }
            ReasoningDialect.GEMINI_BUDGET -> {
                if (!off || explicitOff) {
                    parameters.add("generationConfig", JsonObject().apply {
                        add("thinkingConfig", JsonObject().apply {
                            addProperty("thinkingBudget", if (off) 0 else budget)
                            addProperty("includeThoughts", !off)
                        })
                    })
                } else warnings += ReasoningWarning.CANNOT_DISABLE
            }
            ReasoningDialect.GEMINI_LEVEL -> {
                if (!off || explicitValue != null) {
                    if (transport == ReasoningTransport.GEMINI_INTERACTIONS) {
                        parameters.add("generation_config", JsonObject().apply {
                            addProperty("thinking_level", value)
                            addProperty("thinking_summaries", "auto")
                        })
                    } else parameters.add("generationConfig", JsonObject().apply {
                        add("thinkingConfig", JsonObject().apply {
                            addProperty("thinkingLevel", value)
                            addProperty("includeThoughts", true)
                        })
                    })
                } else warnings += ReasoningWarning.CANNOT_DISABLE
            }
            ReasoningDialect.THINKING_TYPE -> {
                if (transport == ReasoningTransport.OPENAI_RESPONSES) {
                    parameters.add("reasoning", JsonObject().apply { addProperty("effort", value) })
                } else {
                    parameters.add("thinking", type(if (off) "disabled" else "enabled"))
                    if (!off && (automatic || explicitValue != null)) parameters.addProperty("reasoning_effort", value)
                }
            }
            ReasoningDialect.ENABLE_THINKING -> {
                parameters.addProperty("enable_thinking", !off)
                if (!off && config.budgets[effort] != null) parameters.addProperty("thinking_budget", budget)
            }
            ReasoningDialect.CUSTOM -> Unit
            ReasoningDialect.AUTO -> error("AUTO must resolve to a dialect")
        }
        if (!isCompatible(dialect, transport)) warnings += ReasoningWarning.INCOMPATIBLE_DIALECT
        config.patches[effort]?.let {
            parameters = mergeCustomRequestBody(parameters, it)
        }
        if (off) {
            if (hasExplicitOffParameters(parameters)) warnings.remove(ReasoningWarning.CANNOT_DISABLE)
            else warnings += ReasoningWarning.CANNOT_DISABLE
        }
        return ReasoningResolution(dialect, parameters, warnings)
    }

    fun apply(
        base: JsonObject,
        resolution: ReasoningResolution,
        transport: ReasoningTransport,
    ): JsonObject {
        val body = mergeCustomRequestBody(base, resolution.parameters)
        if (transport == ReasoningTransport.ANTHROPIC) {
            val thinking = body.get("thinking")?.takeIf { it.isJsonObject }?.asJsonObject
            val type = thinking?.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            if (type == "enabled" || type == "adaptive") body.remove("temperature")
            val budget = thinking?.get("budget_tokens")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asBigDecimal?.takeIf { it.signum() > 0 && it <= (Int.MAX_VALUE - 4096).toBigDecimal() }?.toLong()
            val maximum = body.get("max_tokens")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal
            if (type == "enabled" && budget != null && maximum != null && (budget + 4096).toBigDecimal() > maximum) {
                body.addProperty("max_tokens", budget + 4096)
            }
        }
        return body
    }

    fun hasExplicitOffParameters(body: JsonObject): Boolean {
        val thinking = body.get("thinking")?.takeIf { it.isJsonObject }?.asJsonObject
        val reasoning = body.get("reasoning")?.takeIf { it.isJsonObject }?.asJsonObject
        val generation = body.get("generationConfig")?.takeIf { it.isJsonObject }?.asJsonObject
        val gemini = generation?.get("thinkingConfig")?.takeIf { it.isJsonObject }?.asJsonObject
        return thinking?.get("type")?.let { it.isJsonPrimitive && it.asString == "disabled" } == true ||
            body.get("reasoning_effort")?.let { it.isJsonPrimitive && it.asString == "none" } == true ||
            reasoning?.get("effort")?.let { it.isJsonPrimitive && it.asString == "none" } == true ||
            body.get("enable_thinking")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean } == true ||
            gemini?.get("thinkingBudget")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble == 0.0 } == true
    }

    fun diagnostics(body: JsonObject): Set<ReasoningWarning> {
        val warnings = linkedSetOf<ReasoningWarning>()
        val thinking = body.get("thinking")?.takeIf { it.isJsonObject }?.asJsonObject
        val type = thinking?.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        val budget = thinking?.get("budget_tokens")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        val maximum = body.get("max_tokens")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        if (budget != null && maximum != null && budget >= maximum) warnings += ReasoningWarning.BUDGET_EXCEEDS_OUTPUT
        if ((type == "disabled" || type == "adaptive") && thinking?.has("budget_tokens") == true) {
            warnings += ReasoningWarning.CONFLICTING_PARAMETERS
        }
        val outputConfig = body.get("output_config")?.takeIf { it.isJsonObject }?.asJsonObject
        if (type == "disabled" && outputConfig?.has("effort") == true) warnings += ReasoningWarning.CONFLICTING_PARAMETERS
        if ((type == "enabled" || type == "adaptive") && body.has("temperature") && !body.get("temperature").isJsonNull) {
            warnings += ReasoningWarning.CONFLICTING_PARAMETERS
        }
        val generation = body.get("generationConfig")?.takeIf { it.isJsonObject }?.asJsonObject
        val gemini = generation?.get("thinkingConfig")?.takeIf { it.isJsonObject }?.asJsonObject
        if (gemini?.has("thinkingBudget") == true && gemini.has("thinkingLevel")) warnings += ReasoningWarning.CONFLICTING_PARAMETERS
        val interactions = body.get("generation_config")?.takeIf { it.isJsonObject }?.asJsonObject
        listOf(gemini?.get("thinkingLevel"), interactions?.get("thinking_level")).forEach { level ->
            if (level?.isJsonPrimitive == true && level.asJsonPrimitive.isString &&
                level.asString.lowercase(Locale.ROOT) !in setOf("minimal", "low", "medium", "high")) {
                warnings += ReasoningWarning.LEVEL_UNVERIFIED
            }
        }
        return warnings
    }

    private fun automaticDialect(model: String, baseUrl: String, transport: ReasoningTransport, effort: ReasoningEffort): ReasoningDialect {
        val name = canonicalModelName(model)
        return when (transport) {
            ReasoningTransport.ANTHROPIC -> {
                if (claudeThinkingProfile(model).adaptive) ReasoningDialect.ANTHROPIC_ADAPTIVE
                else ReasoningDialect.ANTHROPIC_BUDGET
            }
            ReasoningTransport.GEMINI_CONTENT -> {
                if (Regex("""^gemini-[34](?=$|[-.:@])""").containsMatchIn(name)) ReasoningDialect.GEMINI_LEVEL
                else ReasoningDialect.GEMINI_BUDGET
            }
            ReasoningTransport.GEMINI_INTERACTIONS -> ReasoningDialect.GEMINI_LEVEL
            else -> {
                val host = runCatching { URI(baseUrl.trim()).host?.lowercase(Locale.ROOT)?.trimEnd('.') }.getOrNull().orEmpty()
                val thinkingType = host in setOf("api.deepseek.com", "api.moonshot.cn", "api.moonshot.ai", "open.bigmodel.cn", "api.z.ai", "api.minimax.io", "api.minimaxi.com", "api.minimax.chat")
                val dashscope = host in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com")
                val deepseekEffort = host == "api.deepseek.com" && Regex("""^deepseek-(?:v4(?:[.-]\d+)?|flash)(?=$|[-.:@])""").containsMatchIn(name)
                val thinkingOnly = name == "qwq" || name.startsWith("qwq-") || name in setOf(
                    "qwen3-thinking", "qwen3-next-80b-a3b-thinking", "qwen3-235b-a22b-thinking-2507", "qwen3-30b-a3b-thinking-2507",
                    "qwen3.7-max-preview", "qwen3.7-max-2026-05-17", "qwen3.8-2.4t-a95b"
                )
                when {
                    deepseekEffort -> ReasoningDialect.THINKING_TYPE
                    effort != ReasoningEffort.NONE -> ReasoningDialect.OPENAI_EFFORT
                    thinkingType && (transport == ReasoningTransport.OPENAI_CHAT || name.startsWith("deepseek-")) -> ReasoningDialect.THINKING_TYPE
                    transport == ReasoningTransport.OPENAI_CHAT && dashscope && thinkingOnly -> ReasoningDialect.OPENAI_EFFORT
                    transport == ReasoningTransport.OPENAI_CHAT && dashscope -> ReasoningDialect.ENABLE_THINKING
                    else -> ReasoningDialect.OPENAI_EFFORT
                }
            }
        }
    }

    private data class ClaudeThinkingProfile(val adaptive: Boolean, val canDisable: Boolean)

    private fun canonicalModelName(model: String): String =
        model.trim().lowercase(Locale.ROOT).substringAfterLast('/').replace('_', '-')

    private fun claudeThinkingProfile(model: String): ClaudeThinkingProfile {
        val name = canonicalModelName(model)
        if (Regex("""(?:^|[.:])claude-mythos-preview(?=$|[-.:@])""").containsMatchIn(name)) {
            return ClaudeThinkingProfile(adaptive = true, canDisable = false)
        }
        val match = Regex("""(?:^|[.:])claude-(opus|sonnet|haiku|fable|mythos)-(\d+)(?:[.-](\d{1,2})(?=$|[-.:@]))?(?=$|[-.:@])""").find(name)
        val family = match?.groupValues?.get(1)
        val major = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val minor = match?.groupValues?.get(3)?.toIntOrNull() ?: 0
        val adaptive = major >= 5 || (family in setOf("opus", "sonnet") && major == 4 && minor >= 6)
        val cannotDisable = major == 5 && (
            (family in setOf("fable", "mythos") && minor in 0..1) ||
                (family in setOf("opus", "sonnet") && minor == 5)
            )
        return ClaudeThinkingProfile(adaptive, !cannotDisable)
    }

    private fun isCompatible(dialect: ReasoningDialect, transport: ReasoningTransport): Boolean = when (transport) {
        ReasoningTransport.OPENAI_CHAT, ReasoningTransport.OPENAI_RESPONSES -> true
        ReasoningTransport.ANTHROPIC -> dialect in listOf(ReasoningDialect.ANTHROPIC_BUDGET, ReasoningDialect.ANTHROPIC_ADAPTIVE, ReasoningDialect.CUSTOM)
        ReasoningTransport.GEMINI_CONTENT -> dialect in listOf(ReasoningDialect.GEMINI_BUDGET, ReasoningDialect.GEMINI_LEVEL, ReasoningDialect.CUSTOM)
        ReasoningTransport.GEMINI_INTERACTIONS -> dialect in listOf(ReasoningDialect.GEMINI_LEVEL, ReasoningDialect.CUSTOM)
    }

    private fun type(value: String) = JsonObject().apply { addProperty("type", value) }
}
