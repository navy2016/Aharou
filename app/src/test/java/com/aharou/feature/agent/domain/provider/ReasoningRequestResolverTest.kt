package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.data.local.mergeCustomRequestBody
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.aharou.feature.settings.domain.model.ReasoningDialect
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningRequestResolverTest {
    private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject

    @Test
    fun defaultDoesNotInjectAnyDialectOrPatch() {
        ReasoningTransport.entries.forEach { transport ->
            ReasoningDialect.entries.forEach { dialect ->
                val resolved = ReasoningRequestResolver.resolve(
                    "model", "https://example.com", transport, ReasoningEffort.DEFAULT,
                    ReasoningConfig(dialect, patches = mapOf(ReasoningEffort.HIGH to json("""{"thinking":{"type":"enabled"}}""")))
                )
                assertEquals(0, resolved.parameters.size())
            }
        }
    }

    @Test
    fun automaticOpenAiKeepsExistingMappingAndManualEffortCanExpressMax() {
        val automatic = ReasoningRequestResolver.resolve("gpt-5", "https://example.com", ReasoningTransport.OPENAI_CHAT, ReasoningEffort.MAX)
        assertEquals("high", automatic.parameters["reasoning_effort"].asString)
        assertTrue(ReasoningWarning.LEVEL_MAPPED in automatic.warnings)
        val manual = ReasoningRequestResolver.resolve("gpt-5", "https://example.com", ReasoningTransport.OPENAI_CHAT, ReasoningEffort.MAX,
            ReasoningConfig(effortValues = mapOf(ReasoningEffort.MAX to "max")))
        assertEquals("max", manual.parameters["reasoning_effort"].asString)
        assertFalse(ReasoningWarning.LEVEL_MAPPED in manual.warnings)
    }

    @Test
    fun unknownGatewayDoesNotPretendNoneDisablesThinking() {
        val automatic = ReasoningRequestResolver.resolve("gpt-5", "https://example.com", ReasoningTransport.OPENAI_CHAT, ReasoningEffort.NONE)
        assertEquals(0, automatic.parameters.size())
        assertTrue(ReasoningWarning.CANNOT_DISABLE in automatic.warnings)
        val manual = ReasoningRequestResolver.resolve("gpt-5", "https://example.com", ReasoningTransport.OPENAI_CHAT, ReasoningEffort.NONE,
            ReasoningConfig(dialect = ReasoningDialect.OPENAI_EFFORT))
        assertEquals("none", manual.parameters["reasoning_effort"].asString)
    }

    @Test
    fun emptyOffPatchDoesNotClaimThatThinkingWasDisabled() {
        listOf(ReasoningConfig(patches = mapOf(ReasoningEffort.NONE to JsonObject())),
            ReasoningConfig(dialect = ReasoningDialect.CUSTOM)).forEach { config ->
            val result = ReasoningRequestResolver.resolve("model", "https://example.com", ReasoningTransport.OPENAI_CHAT, ReasoningEffort.NONE, config)
            assertEquals(0, result.parameters.size())
            assertTrue(ReasoningWarning.CANNOT_DISABLE in result.warnings)
        }
    }

    @Test
    fun knownProviderOffFieldsMatchTheirTransport() {
        val chat = ReasoningRequestResolver.resolve("deepseek-v4", "https://api.deepseek.com", ReasoningTransport.OPENAI_CHAT, ReasoningEffort.NONE)
        assertEquals(json("""{"thinking":{"type":"disabled"}}"""), chat.parameters)
        val responses = ReasoningRequestResolver.resolve("deepseek-v4", "https://api.deepseek.com", ReasoningTransport.OPENAI_RESPONSES, ReasoningEffort.NONE)
        assertEquals(json("""{"reasoning":{"effort":"none"}}"""), responses.parameters)
        val dashscope = ReasoningRequestResolver.resolve("qwen", "https://dashscope.aliyuncs.com/compatible-mode", ReasoningTransport.OPENAI_CHAT, ReasoningEffort.NONE)
        assertEquals(json("""{"enable_thinking":false}"""), dashscope.parameters)
        val unrecognized = ReasoningRequestResolver.resolve("deepseek-v4", "https://example.com/api.deepseek.com", ReasoningTransport.OPENAI_CHAT, ReasoningEffort.NONE)
        assertEquals(0, unrecognized.parameters.size())
    }

    @Test
    fun anthropicBudgetKeepsRoomForOutputAndNeverOverflows() {
        val resolved = ReasoningRequestResolver.resolve("claude-sonnet-4-5", "", ReasoningTransport.ANTHROPIC, ReasoningEffort.HIGH,
            ReasoningConfig(budgets = mapOf(ReasoningEffort.HIGH to Int.MAX_VALUE - 4096)))
        val body = ReasoningRequestResolver.apply(json("""{"max_tokens":16384,"temperature":0.7}"""), resolved, ReasoningTransport.ANTHROPIC)
        assertEquals(Int.MAX_VALUE, body["max_tokens"].asInt)
        assertFalse(body.has("temperature"))
        assertTrue(ReasoningRequestResolver.diagnostics(body).isEmpty())
    }

    @Test
    fun smallAnthropicBudgetExplainsMinimumAdjustment() {
        val resolved = ReasoningRequestResolver.resolve("claude-sonnet-4-5", "", ReasoningTransport.ANTHROPIC, ReasoningEffort.LOW,
            ReasoningConfig(budgets = mapOf(ReasoningEffort.LOW to 1)))
        assertEquals(1024, resolved.parameters.getAsJsonObject("thinking")["budget_tokens"].asInt)
        assertTrue(ReasoningWarning.BUDGET_ADJUSTED in resolved.warnings)
    }

    @Test
    fun adaptiveAndGeminiTransportChooseDifferentEnvelopes() {
        val adaptive = ReasoningRequestResolver.resolve("claude-sonnet-4-6", "", ReasoningTransport.ANTHROPIC, ReasoningEffort.HIGH)
        assertEquals("adaptive", adaptive.parameters.getAsJsonObject("thinking")["type"].asString)
        assertEquals("high", adaptive.parameters.getAsJsonObject("output_config")["effort"].asString)
        val content = ReasoningRequestResolver.resolve("gemini-3-pro", "", ReasoningTransport.GEMINI_CONTENT, ReasoningEffort.HIGH)
        assertTrue(content.parameters.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").has("thinkingLevel"))
        val interactions = ReasoningRequestResolver.resolve("gemini-3-pro", "", ReasoningTransport.GEMINI_INTERACTIONS, ReasoningEffort.HIGH)
        assertEquals("high", interactions.parameters.getAsJsonObject("generation_config")["thinking_level"].asString)
        assertFalse(interactions.parameters.has("generationConfig"))
    }

    @Test
    fun perLevelPatchAndFixedBodyKeepNullAndFinalOverrideWithoutMutatingSources() {
        val config = ReasoningConfig(dialect = ReasoningDialect.ANTHROPIC_BUDGET,
            patches = mapOf(ReasoningEffort.HIGH to json("""{"thinking":{"type":"adaptive"},"extra":null}""")))
        val resolved = ReasoningRequestResolver.resolve("model", "", ReasoningTransport.ANTHROPIC, ReasoningEffort.HIGH, config)
        assertFalse(resolved.parameters.getAsJsonObject("thinking").has("budget_tokens"))
        val generated = ReasoningRequestResolver.apply(json("""{"max_tokens":16384}"""), resolved, ReasoningTransport.ANTHROPIC)
        val fixed = json("""{"thinking":null,"extra":null,"temperature":0.2}""")
        val final = mergeCustomRequestBody(generated, fixed)
        assertTrue(final["thinking"].isJsonNull)
        assertTrue(final["extra"].isJsonNull)
        assertEquals(0.2, final["temperature"].asDouble, 0.0)
        assertEquals("adaptive", config.patches.getValue(ReasoningEffort.HIGH).getAsJsonObject("thinking")["type"].asString)
    }

    @Test
    fun diagnosticsExplainConflictsWithoutChangingFixedJson() {
        val body = json("""{"thinking":{"type":"enabled","budget_tokens":8192},"max_tokens":4096,"temperature":0.6,"generationConfig":{"thinkingConfig":{"thinkingLevel":"high","thinkingBudget":4096}}}""")
        val original = body.deepCopy()
        val warnings = ReasoningRequestResolver.diagnostics(body)
        assertTrue(ReasoningWarning.BUDGET_EXCEEDS_OUTPUT in warnings)
        assertTrue(ReasoningWarning.CONFLICTING_PARAMETERS in warnings)
        assertEquals(original, body)
    }
}
