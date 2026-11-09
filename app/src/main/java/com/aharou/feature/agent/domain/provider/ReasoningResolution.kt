package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.domain.model.ReasoningDialect
import com.google.gson.JsonObject

enum class ReasoningTransport {
    OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC, GEMINI_CONTENT, GEMINI_INTERACTIONS,
}

enum class ReasoningWarning {
    CANNOT_DISABLE, LEVEL_UNVERIFIED, BUDGET_EXCEEDS_OUTPUT, INCOMPATIBLE_DIALECT,
    CONFLICTING_PARAMETERS, BUDGET_ADJUSTED, BUDGET_OUTSIDE_MODEL_RANGE,
}

data class ReasoningResolution(
    val dialect: ReasoningDialect,
    val parameters: JsonObject,
    val warnings: Set<ReasoningWarning> = emptySet(),
)

enum class ReasoningOverrideScope { GLOBAL, PROVIDER, MODEL }

data class ReasoningOverride(
    val scope: ReasoningOverrideScope,
    val paths: List<String>,
)

data class ReasoningPreview(
    val selectedEffort: ReasoningEffort,
    val dialect: ReasoningDialect,
    val parameters: String,
    val overrides: List<ReasoningOverride>,
    val warnings: Set<ReasoningWarning>,
)
