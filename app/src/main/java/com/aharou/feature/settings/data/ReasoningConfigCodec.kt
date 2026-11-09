package com.aharou.feature.settings.data

import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.aharou.feature.settings.domain.model.ReasoningDialect
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

object ReasoningConfigCodec {
    // Anthropic 还需为正文预留 4096 tokens，避免叠加预算时溢出。
    private const val MAX_BUDGET = Int.MAX_VALUE - 4096
    private val fields = setOf("dialect", "effortValues", "budgets", "patches")

    fun encode(config: ReasoningConfig): JsonObject = JsonObject().apply {
        addProperty("dialect", config.dialect.name.lowercase())
        add("effortValues", encodeEntries(config.effortValues) { _, value -> JsonPrimitive(value) })
        add("budgets", encodeEntries(config.budgets) { effort, value ->
            validateBudget(effort, value)
            JsonPrimitive(value)
        })
        add("patches", encodeEntries(config.patches) { _, value -> value.deepCopy() })
    }

    fun decode(json: JsonObject): ReasoningConfig {
        require(json.keySet().all { it in fields }) { "Unknown reasoning config field" }
        val dialect = json.get("dialect")?.let { value ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "dialect must be a string" }
            ReasoningDialect.entries.firstOrNull { it.name.equals(value.asString, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown reasoning dialect")
        } ?: ReasoningDialect.AUTO
        return ReasoningConfig(
            dialect = dialect,
            effortValues = decodeEntries(json, "effortValues") { _, value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    "effortValues values must be strings"
                }
                value.asString
            },
            budgets = decodeEntries(json, "budgets") { effort, value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                    "budgets values must be integers"
                }
                val budget = try {
                    value.asBigDecimal.intValueExact()
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("budgets values must be integers within the supported range")
                } catch (_: NumberFormatException) {
                    throw IllegalArgumentException("budgets values must be integers")
                }
                validateBudget(effort, budget)
                budget
            },
            patches = decodeEntries(json, "patches") { _, value ->
                require(value.isJsonObject) { "patches values must be JSON objects" }
                value.asJsonObject.deepCopy()
            },
        )
    }

    private fun <T> encodeEntries(
        values: Map<ReasoningEffort, T>,
        encodeValue: (ReasoningEffort, T) -> JsonElement,
    ): JsonObject = JsonObject().apply {
        values.forEach { (effort, value) ->
            require(effort != ReasoningEffort.DEFAULT) { "DEFAULT cannot be configured" }
            add(effort.name.lowercase(), encodeValue(effort, value))
        }
    }

    private fun <T> decodeEntries(
        json: JsonObject,
        field: String,
        decodeValue: (ReasoningEffort, JsonElement) -> T,
    ): Map<ReasoningEffort, T> {
        val values = json.get(field) ?: return emptyMap()
        require(values.isJsonObject) { "$field must be a JSON object" }
        val result = linkedMapOf<ReasoningEffort, T>()
        values.asJsonObject.entrySet().forEach { (key, value) ->
            val effort = ReasoningEffort.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown effort in $field")
            require(effort != ReasoningEffort.DEFAULT) { "DEFAULT cannot be configured" }
            require(effort !in result) { "Duplicate effort in $field" }
            result[effort] = decodeValue(effort, value)
        }
        return result
    }

    private fun validateBudget(effort: ReasoningEffort, budget: Int) {
        require(effort != ReasoningEffort.NONE) { "NONE cannot have a budget" }
        require(budget in 1..MAX_BUDGET) { "budgets values must be between 1 and $MAX_BUDGET" }
    }
}
