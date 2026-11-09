package com.aharou.feature.settings.domain.model

import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.google.gson.JsonObject

enum class ReasoningDialect {
    AUTO, OPENAI_EFFORT, ANTHROPIC_BUDGET, ANTHROPIC_ADAPTIVE,
    GEMINI_BUDGET, GEMINI_LEVEL, THINKING_TYPE, ENABLE_THINKING, CUSTOM,
}

data class ReasoningConfig(
    val dialect: ReasoningDialect = ReasoningDialect.AUTO,
    val effortValues: Map<ReasoningEffort, String> = emptyMap(),
    val budgets: Map<ReasoningEffort, Int> = emptyMap(),
    val patches: Map<ReasoningEffort, JsonObject> = emptyMap(),
) {
    val isAutomatic: Boolean
        get() = dialect == ReasoningDialect.AUTO && effortValues.isEmpty() && budgets.isEmpty() && patches.isEmpty()
}
