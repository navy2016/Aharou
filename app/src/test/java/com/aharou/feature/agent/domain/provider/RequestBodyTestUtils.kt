package com.aharou.feature.agent.domain.provider

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.RequestBody
import okio.Buffer

internal fun RequestBody.decodeJsonObject(): JsonObject {
    val buffer = Buffer()
    writeTo(buffer)
    return JsonParser.parseString(buffer.readUtf8()).asJsonObject
}

internal fun RequestBody.decodeJsonMap(): Map<String, Any?> =
    decodeJsonObject().entrySet().associate { (key, value) -> key to value.toRequestValue() }

private fun JsonElement.toRequestValue(): Any? = when {
    isJsonNull -> null
    isJsonObject -> asJsonObject.entrySet().associate { (key, value) -> key to value.toRequestValue() }
    isJsonArray -> asJsonArray.map { it.toRequestValue() }
    else -> asJsonPrimitive.let { value ->
        when {
            value.isBoolean -> value.asBoolean
            value.isString -> value.asString
            else -> value.asString.let { it.toIntOrNull() ?: it.toLongOrNull() ?: it.toDouble() }
        }
    }
}
