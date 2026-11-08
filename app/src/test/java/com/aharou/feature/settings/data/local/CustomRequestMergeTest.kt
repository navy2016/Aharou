package com.aharou.feature.settings.data.local

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomRequestMergeTest {
    private fun obj(raw: String) = JsonParser.parseString(raw).asJsonObject

    @Test
    fun modelOverridesProviderAndGlobalWithCaseInsensitiveHeaders() {
        val merged = mergeCustomRequestOverrides(
            CustomRequestOverride(mapOf("X-Token" to "global"), obj("""{"config":{"a":1,"b":2},"items":[1]}""")),
            CustomRequestOverride(mapOf("x-token" to "provider"), obj("""{"config":{"b":3},"items":[2]}""")),
            CustomRequestOverride(mapOf("X-TOKEN" to "model"), obj("""{"config":{"c":4},"empty":null}""")),
        )
        assertEquals(mapOf("X-TOKEN" to "model"), merged.headers)
        assertEquals(obj("""{"config":{"a":1,"b":3,"c":4},"items":[2],"empty":null}"""), merged.body)
    }

    @Test
    fun reasoningObjectsReplaceRatherThanMerge() {
        val base = obj("""{"thinking":{"type":"enabled","budget_tokens":100},"nested":{"a":1}}""")
        val patch = obj("""{"thinking":{"type":"disabled"},"nested":{"b":2}}""")
        val merged = mergeCustomRequestBody(base, patch)
        assertEquals(obj("""{"thinking":{"type":"disabled"},"nested":{"a":1,"b":2}}"""), merged)
        assertTrue(base.getAsJsonObject("thinking").has("budget_tokens"))
    }
}
