package com.aharou.feature.settings.data

import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

internal fun reasoningConfigField(
    path: String,
    read: () -> ReasoningConfig,
    write: (ReasoningConfig) -> Unit,
): ConfigField = ClosureField(
    path = path,
    displayName = "模型推理配置",
    description = "JSON 对象：dialect / effortValues / budgets / patches；DEFAULT 不可映射，NONE 不可设置预算。",
    valueSchema = ConfigSchema.Json,
    risk = ConfigRisk.SENSITIVE,
    reader = {
        try {
            ConfigValue.Str(ReasoningConfigCodec.encode(read()).toString())
        } catch (e: IllegalArgumentException) {
            throw ConfigError.InvalidValue(e.message ?: "Invalid reasoning config")
        }
    },
    writer = { value ->
        val raw = when (value) {
            is ConfigValue.Str -> value.value
            is ConfigValue.Obj -> value.jsonString()
            else -> throw ConfigError.TypeMismatch("JSON object")
        }
        val config = try {
            val json = JsonParser.parseString(raw)
            require(json.isJsonObject) { "reasoning_config must be a JSON object" }
            ReasoningConfigCodec.decode(json.asJsonObject)
        } catch (_: JsonParseException) {
            throw ConfigError.InvalidValue("reasoning_config must be a JSON object")
        } catch (e: IllegalArgumentException) {
            throw ConfigError.InvalidValue(e.message ?: "Invalid reasoning config")
        }
        write(config)
    },
)
