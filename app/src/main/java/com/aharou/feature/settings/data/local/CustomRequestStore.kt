package com.aharou.feature.settings.data.local

import android.content.Context
import com.aharou.core.security.KeystoreCipher
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * cuplivo 风格的三级请求覆盖：全局、供应商、模型。内容仅影响请求发送，不改会话历史。
 */
data class CustomRequestOverride(
    val headers: Map<String, String> = emptyMap(),
    val body: JsonObject? = null,
)

@Singleton
class CustomRequestStore @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getGlobal(): CustomRequestOverride = get(KEY_GLOBAL)

    fun getProvider(providerId: String): CustomRequestOverride = get(providerKey(providerId))

    fun getModel(providerId: String, model: String): CustomRequestOverride =
        get(modelKey(providerId, model))

    fun setGlobal(value: CustomRequestOverride) = set(KEY_GLOBAL, value)

    fun setProvider(providerId: String, value: CustomRequestOverride) = set(providerKey(providerId), value)

    fun setModel(providerId: String, model: String, value: CustomRequestOverride) =
        set(modelKey(providerId, model), value)

    fun removeProvider(providerId: String) {
        val prefix = "model:$providerId:"
        val editor = prefs.edit().remove(providerKey(providerId))
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun resolve(providerId: String, model: String, existingHeaders: Map<String, String>): CustomRequestOverride {
        val provider = getProvider(providerId)
        val providerWithExistingHeaders = mergeCustomRequestOverrides(
            CustomRequestOverride(headers = existingHeaders), provider, CustomRequestOverride()
        )
        return mergeCustomRequestOverrides(getGlobal(), providerWithExistingHeaders, getModel(providerId, model))
    }

    private fun get(key: String): CustomRequestOverride {
        val raw = prefs.getString(key, null)?.let(KeystoreCipher::decryptString) ?: return CustomRequestOverride()
        return runCatching {
            val obj = JsonParser.parseString(raw).asJsonObject
            val headers = obj.getAsJsonObject("headers")?.entrySet()?.associate { it.key to it.value.asString }
                ?: emptyMap()
            val body = obj.get("body")?.takeIf { it.isJsonObject }?.asJsonObject
            CustomRequestOverride(headers, body)
        }.getOrDefault(CustomRequestOverride())
    }

    private fun set(key: String, value: CustomRequestOverride) {
        val obj = JsonObject().apply {
            add("headers", gson.toJsonTree(value.headers))
            value.body?.let { add("body", it) }
        }
        prefs.edit().putString(key, KeystoreCipher.encryptString(obj.toString())).apply()
    }

    private fun providerKey(providerId: String) = "provider:$providerId"
    private fun modelKey(providerId: String, model: String) = "model:$providerId:$model"

    private companion object {
        const val PREFS = "aharou_custom_request"
        const val KEY_GLOBAL = "global"
    }
}

/** 合并三级配置：模型覆盖供应商，供应商覆盖全局；头名称按大小写不敏感处理。 */
fun mergeCustomRequestOverrides(
    global: CustomRequestOverride,
    provider: CustomRequestOverride,
    model: CustomRequestOverride,
): CustomRequestOverride {
    val headers = linkedMapOf<String, String>()
    fun putHeaders(values: Map<String, String>) {
        values.forEach { (name, value) ->
            headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { headers.remove(it) }
            if (name.isNotBlank()) headers[name] = value
        }
    }
    putHeaders(global.headers)
    putHeaders(provider.headers)
    putHeaders(model.headers)
    val body = listOf(global.body, provider.body, model.body)
        .filterNotNull()
        .fold(JsonObject()) { base, next -> mergeCustomRequestBody(base, next) }
    return CustomRequestOverride(headers, body.takeIf { it.size() > 0 })
}

/** 对象递归合并；数组、标量与 thinking/reasoning 配置整体替换。 */
fun mergeCustomRequestBody(base: JsonObject, patch: JsonObject): JsonObject {
    val out = base.deepCopy()
    patch.entrySet().forEach { (key, value) ->
        val current = out.get(key)
        val replaceWhole = key.equals("thinking", true) || key.equals("reasoning", true) ||
            key.equals("thinkingConfig", true)
        if (!replaceWhole && current?.isJsonObject == true && value.isJsonObject) {
            out.add(key, mergeCustomRequestBody(current.asJsonObject, value.asJsonObject))
        } else {
            out.add(key, value.deepCopy())
        }
    }
    return out
}
