package com.aharou.feature.settings.data

import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.settings.data.local.CustomRequestOverride
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Headers

internal fun customRequestConfigFields(
    basePath: String,
    read: () -> CustomRequestOverride,
    write: (CustomRequestOverride) -> Unit,
): List<ConfigField> = listOf(
    ClosureField(
        path = "$basePath.headers",
        displayName = "自定义请求头",
        description = "JSON 对象，字段值必须为字符串；支持 {{API_KEY}} 与 {{SESSION_ID}}。",
        valueSchema = ConfigSchema.Json,
        risk = ConfigRisk.SENSITIVE,
        reader = {
            val obj = JsonObject()
            read().headers.forEach { (name, value) -> obj.addProperty(name, value) }
            ConfigValue.Str(obj.toString())
        },
        writer = { value ->
            val raw = when (value) {
                is ConfigValue.Str -> value.value
                is ConfigValue.Obj -> value.jsonString()
                else -> throw ConfigError.TypeMismatch("JSON object")
            }
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: throw ConfigError.InvalidValue("请求头必须为 JSON 对象")
            val headers = runCatching {
                val builder = Headers.Builder()
                obj.entrySet().associate { (name, json) ->
                    require(json.isJsonPrimitive && json.asJsonPrimitive.isString)
                    builder.add(name, json.asString)
                    name to json.asString
                }
            }.getOrNull() ?: throw ConfigError.InvalidValue("请求头名称或值无效")
            write(read().copy(headers = headers))
        },
    ),
    ClosureField(
        path = "$basePath.body",
        displayName = "自定义请求体",
        description = "JSON 对象，递归覆盖请求体字段。",
        valueSchema = ConfigSchema.Json,
        risk = ConfigRisk.SENSITIVE,
        reader = { ConfigValue.Str(read().body?.toString() ?: "{}") },
        writer = { value ->
            val raw = when (value) {
                is ConfigValue.Str -> value.value
                is ConfigValue.Obj -> value.jsonString()
                else -> throw ConfigError.TypeMismatch("JSON object")
            }
            val body = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: throw ConfigError.InvalidValue("请求体必须为 JSON 对象")
            write(read().copy(body = body.takeIf { it.size() > 0 }))
        },
    ),
)
