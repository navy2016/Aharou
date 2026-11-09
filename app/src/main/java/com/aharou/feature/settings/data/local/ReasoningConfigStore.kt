package com.aharou.feature.settings.data.local

import android.content.Context
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.settings.data.ReasoningConfigCodec
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReasoningConfigStore @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun getModel(providerId: String, model: String): ReasoningConfig {
        val encrypted = prefs.getString(modelKey(providerId, model), null) ?: return ReasoningConfig()
        val raw = KeystoreCipher.decryptString(encrypted)
        if (raw.isEmpty()) return ReasoningConfig()
        val json = try {
            JsonParser.parseString(raw)
        } catch (_: JsonParseException) {
            throw IllegalArgumentException("Stored reasoning config must be a JSON object")
        }
        require(json.isJsonObject) { "Stored reasoning config must be a JSON object" }
        return ReasoningConfigCodec.decode(json.asJsonObject)
    }

    @Synchronized
    fun setModel(providerId: String, model: String, value: ReasoningConfig) {
        if (value.isAutomatic) {
            removeModel(providerId, model)
            return
        }
        val json = ReasoningConfigCodec.encode(value)
        val encrypted = KeystoreCipher.encryptString(json.toString())
        prefs.edit().putString(modelKey(providerId, model), encrypted).apply()
    }

    @Synchronized
    fun removeModel(providerId: String, model: String) {
        prefs.edit().remove(modelKey(providerId, model)).apply()
    }

    @Synchronized
    fun removeProvider(providerId: String) {
        val prefix = providerPrefix(providerId)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    @Synchronized
    fun moveModel(providerId: String, old: String, new: String) {
        if (old == new) return
        val oldKey = modelKey(providerId, old)
        val value = prefs.getString(oldKey, null) ?: return
        prefs.edit().putString(modelKey(providerId, new), value).remove(oldKey).apply()
    }

    // Length-prefixing keeps provider/model names containing ':' from sharing keys or deletion prefixes.
    private fun providerPrefix(providerId: String) = "model:${providerId.length}:$providerId:"
    private fun modelKey(providerId: String, model: String) = "${providerPrefix(providerId)}$model"

    private companion object {
        const val PREFS = "aharou_reasoning_config"
    }
}
