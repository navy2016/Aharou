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
    fun automaticExplicitLevelsArePreservedAcrossTransports() {
        val models = mapOf(
            ReasoningTransport.OPENAI_CHAT to "gpt-5",
            ReasoningTransport.OPENAI_RESPONSES to "gpt-5",
            ReasoningTransport.ANTHROPIC to "claude-sonnet-4-6",
            ReasoningTransport.GEMINI_CONTENT to "gemini-3-pro",
            ReasoningTransport.GEMINI_INTERACTIONS to "gemini-3-pro",
        )
        models.forEach { (transport, model) ->
            listOf(ReasoningEffort.XHIGH, ReasoningEffort.MAX).forEach { effort ->
                val parameters = ReasoningRequestResolver.resolve(model, "https://example.com", transport, effort).parameters
                val actual = when (transport) {
                    ReasoningTransport.OPENAI_CHAT -> parameters["reasoning_effort"]
                    ReasoningTransport.OPENAI_RESPONSES -> parameters.getAsJsonObject("reasoning")["effort"]
                    ReasoningTransport.ANTHROPIC -> parameters.getAsJsonObject("output_config")["effort"]
                    ReasoningTransport.GEMINI_CONTENT -> parameters.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig")["thinkingLevel"]
                    ReasoningTransport.GEMINI_INTERACTIONS -> parameters.getAsJsonObject("generation_config")["thinking_level"]
                }
                assertEquals("$transport/$effort", effort.apiValue, actual.asString)
            }
        }
        val manual = ReasoningRequestResolver.resolve("gpt-5", "https://example.com", ReasoningTransport.OPENAI_CHAT, ReasoningEffort.MAX,
            ReasoningConfig(effortValues = mapOf(ReasoningEffort.MAX to "custom-value")))
        assertEquals("custom-value", manual.parameters["reasoning_effort"].asString)
    }

    @Test
    fun modelIdentificationHandlesPrefixesCaseAndVersionSeparators() {
        listOf(
            " CLAUDE-SONNET-4.6 ", "anthropic/claude-opus-4-6", "us.anthropic.claude-sonnet-4-6-v1:0",
            "Claude_Opus_4_7", "claude-sonnet-5", "anthropic.claude-haiku-5-5", "Claude-Sonnet-5.5",
            "eu.anthropic.claude-opus-5-5", "claude-opus-4-6@20260205", "global.anthropic.claude-fable-5-1",
            "claude-mythos-preview", "anthropic/CLAUDE-MYTHOS-PREVIEW"
        ).forEach { model ->
            val resolved = ReasoningRequestResolver.resolve(model, "", ReasoningTransport.ANTHROPIC, ReasoningEffort.MAX)
            assertEquals(model, ReasoningDialect.ANTHROPIC_ADAPTIVE, resolved.dialect)
            assertEquals("max", resolved.parameters.getAsJsonObject("output_config")["effort"].asString)
        }
        listOf("claude-sonnet-4-20250514", "claude-sonnet-4-5", "notclaude-sonnet-4-6", "anthropic.claude-3-7-sonnet-20250219-v1:0").forEach { model ->
            val resolved = ReasoningRequestResolver.resolve(model, "", ReasoningTransport.ANTHROPIC, ReasoningEffort.HIGH)
            assertEquals(model, ReasoningDialect.ANTHROPIC_BUDGET, resolved.dialect)
        }
        listOf(" Google/GEMINI-3.1-Pro-Preview ", "models/gemini-4-argon", "GEMINI_3_FLASH", "publishers/google/models/gemini-3.8-flash", "google/gemini-4-argon@20261013").forEach { model ->
            val resolved = ReasoningRequestResolver.resolve(model, "", ReasoningTransport.GEMINI_CONTENT, ReasoningEffort.MAX)
            assertEquals(model, ReasoningDialect.GEMINI_LEVEL, resolved.dialect)
        }
        listOf("gemini-2.5-pro", "notgemini-3-pro", "gemini-30-pro").forEach { model ->
            val resolved = ReasoningRequestResolver.resolve(model, "", ReasoningTransport.GEMINI_CONTENT, ReasoningEffort.HIGH)
            assertEquals(model, ReasoningDialect.GEMINI_BUDGET, resolved.dialect)
        }
    }

    @Test
    fun automaticClaudeThinkingOnlyGenerationsKeepOffWarningAndAllowManualOverride() {
        listOf("claude-mythos-preview", "anthropic/claude-fable-5", "claude-mythos-5.1", "eu.anthropic.claude-opus-5-5", "Claude-Sonnet-5.5").forEach { model ->
            val off = ReasoningRequestResolver.resolve(model, "", ReasoningTransport.ANTHROPIC, ReasoningEffort.NONE)
            assertEquals(model, 0, off.parameters.size())
            assertTrue(model, ReasoningWarning.CANNOT_DISABLE in off.warnings)
            val default = ReasoningRequestResolver.resolve(model, "", ReasoningTransport.ANTHROPIC, ReasoningEffort.DEFAULT)
            assertEquals(0, default.parameters.size())
        }
        listOf("claude-sonnet-5", "claude-opus-4.8", "claude-haiku-5.5").forEach { model ->
            val off = ReasoningRequestResolver.resolve(model, "", ReasoningTransport.ANTHROPIC, ReasoningEffort.NONE)
            assertEquals(model, "disabled", off.parameters.getAsJsonObject("thinking")["type"].asString)
        }
        val manual = ReasoningRequestResolver.resolve("claude-opus-5.5", "", ReasoningTransport.ANTHROPIC, ReasoningEffort.NONE,
            ReasoningConfig(dialect = ReasoningDialect.ANTHROPIC_ADAPTIVE))
        assertEquals("disabled", manual.parameters.getAsJsonObject("thinking")["type"].asString)
    }

    @Test
    fun providerHostRecognitionUsesExactDomains() {
        listOf("https://dashscope.aliyuncs.com", "https://DASHSCOPE-INTL.ALIYUNCS.COM./compatible-mode", "https://dashscope-us.aliyuncs.com").forEach { base ->
            val resolved = ReasoningRequestResolver.resolve("qwen", base, ReasoningTransport.OPENAI_CHAT, ReasoningEffort.NONE)
            assertEquals(json("""{"enable_thinking":false}"""), resolved.parameters)
        }
        listOf("https://dashscope.example.com", "https://dashscope.aliyuncs.com.example.com", "https://example.com/dashscope", "https://dashscope.aliyuncs.com@example.com").forEach { base ->
            val resolved = ReasoningRequestResolver.resolve("qwen", base, ReasoningTransport.OPENAI_CHAT, ReasoningEffort.NONE)
            assertEquals(base, 0, resolved.parameters.size())
            assertTrue(ReasoningWarning.CANNOT_DISABLE in resolved.warnings)
        }
    }

    @Test
    fun geminiUnverifiedLevelsAreDiagnosedWithoutRewriting() {
        listOf(ReasoningTransport.GEMINI_CONTENT, ReasoningTransport.GEMINI_INTERACTIONS).forEach { transport ->
            listOf(ReasoningEffort.XHIGH, ReasoningEffort.MAX).forEach { effort ->
                val body = ReasoningRequestResolver.resolve("gemini-3-pro", "", transport, effort).parameters
                val before = body.deepCopy()
                assertTrue(ReasoningWarning.LEVEL_UNVERIFIED in ReasoningRequestResolver.diagnostics(body))
                assertEquals(before, body)
            }
            val high = ReasoningRequestResolver.resolve("gemini-3-pro", "", transport, ReasoningEffort.HIGH).parameters
            assertFalse(ReasoningWarning.LEVEL_UNVERIFIED in ReasoningRequestResolver.diagnostics(high))
        }
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
    fun dashscopeThinkingOnlyModelsDoNotReceiveAutomaticOffSwitches() {
        val base = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        listOf("qwq-32b", "qwq-plus", "qwen3-thinking", "Qwen/qwen3-235b-a22b-thinking-2507", "qwen3.7-max-preview", "qwen3.8-2.4t-a95b").forEach { model ->
            val resolved = ReasoningRequestResolver.resolve(model, base, ReasoningTransport.OPENAI_CHAT, ReasoningEffort.NONE)
            assertEquals(model, 0, resolved.parameters.size())
            assertTrue(ReasoningWarning.CANNOT_DISABLE in resolved.warnings)
        }
        val manual = ReasoningRequestResolver.resolve("qwq-32b", base, ReasoningTransport.OPENAI_CHAT, ReasoningEffort.NONE,
            ReasoningConfig(dialect = ReasoningDialect.ENABLE_THINKING))
        assertEquals(false, manual.parameters["enable_thinking"].asBoolean)
        val hybrid = ReasoningRequestResolver.resolve("qwen-plus", base, ReasoningTransport.OPENAI_CHAT, ReasoningEffort.NONE)
        assertEquals(false, hybrid.parameters["enable_thinking"].asBoolean)
    }

    @Test
    fun officialDeepSeekEnablesThinkingAndPreservesExplicitEffort() {
        listOf("deepseek-v4-pro", "deepseek-v4-flash", "deepseek-flash", "DeepSeek/DEEPSEEK-V4.1-FLASH").forEach { model ->
            listOf(ReasoningEffort.LOW, ReasoningEffort.XHIGH, ReasoningEffort.MAX).forEach { effort ->
                val chat = ReasoningRequestResolver.resolve(model, "https://api.deepseek.com", ReasoningTransport.OPENAI_CHAT, effort)
                assertEquals("enabled", chat.parameters.getAsJsonObject("thinking")["type"].asString)
                assertEquals(effort.apiValue, chat.parameters["reasoning_effort"].asString)
                val responses = ReasoningRequestResolver.resolve(model, "https://api.deepseek.com", ReasoningTransport.OPENAI_RESPONSES, effort)
                assertEquals(effort.apiValue, responses.parameters.getAsJsonObject("reasoning")["effort"].asString)
                assertFalse(responses.parameters.has("thinking"))
            }
            val default = ReasoningRequestResolver.resolve(model, "https://api.deepseek.com", ReasoningTransport.OPENAI_CHAT, ReasoningEffort.DEFAULT)
            assertEquals(0, default.parameters.size())
        }
        listOf("https://api.deepseek.com.example.com", "https://example.com").forEach { base ->
            val gateway = ReasoningRequestResolver.resolve("deepseek-v4-pro", base, ReasoningTransport.OPENAI_CHAT, ReasoningEffort.MAX)
            assertFalse(gateway.parameters.has("thinking"))
            assertEquals("max", gateway.parameters["reasoning_effort"].asString)
        }
        val older = ReasoningRequestResolver.resolve("deepseek-v3", "https://api.deepseek.com", ReasoningTransport.OPENAI_CHAT, ReasoningEffort.HIGH)
        assertFalse(older.parameters.has("thinking"))
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
