package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.data.local.CustomRequestOverride
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningPreviewTest {
    private val provider = AIProviderConfig("p", "Provider", ProviderType.OPENAI, "", baseUrl = "https://example.com", defaultModel = "model")
    private fun custom(body: String) = CustomRequestOverride(body = JsonParser.parseString(body).asJsonObject)

    @Test
    fun modelFixedEffortWinsAndSourceIsVisibleWithoutChangingSelection() {
        val preview = buildReasoningPreview(provider, "model", ReasoningEffort.HIGH, ReasoningConfig(), null,
            custom("""{"reasoning_effort":"medium"}"""), custom("""{"reasoning_effort":"high"}"""), custom("""{"reasoning_effort":"low"}"""))
        assertEquals(ReasoningEffort.HIGH, preview.selectedEffort)
        assertEquals("low", JsonParser.parseString(preview.parameters).asJsonObject["reasoning_effort"].asString)
        assertEquals(listOf(ReasoningOverride(ReasoningOverrideScope.MODEL, listOf("reasoning_effort"))), preview.overrides)
    }

    @Test
    fun overriddenAncestorDoesNotRemoveGeneratedSiblingOrKeepStaleSource() {
        val gemini = provider.copy(type = ProviderType.GEMINI)
        val preview = buildReasoningPreview(gemini, "gemini-3-pro", ReasoningEffort.HIGH, ReasoningConfig(), null,
            custom("""{"generationConfig":null}"""), custom("""{"generationConfig":{"temperature":0.8}}"""), CustomRequestOverride())
        val body = JsonParser.parseString(preview.parameters).asJsonObject
        assertEquals("high", body.getAsJsonObject("generationConfig.thinkingConfig")["thinkingLevel"].asString)
        assertTrue(preview.overrides.isEmpty())
    }

    @Test
    fun explicitNullIsDisplayedAndAttributed() {
        val preview = buildReasoningPreview(provider, "model", ReasoningEffort.HIGH, ReasoningConfig(), null,
            CustomRequestOverride(), custom("""{"reasoning_effort":null}"""), CustomRequestOverride())
        assertTrue(JsonParser.parseString(preview.parameters).asJsonObject["reasoning_effort"].isJsonNull)
        assertEquals(ReasoningOverrideScope.PROVIDER, preview.overrides.single().scope)
    }

    @Test
    fun previewMasksUnknownValuesAndNeverIncludesHeadersOrMessages() {
        val config = ReasoningConfig(patches = mapOf(ReasoningEffort.HIGH to JsonParser.parseString(
            """{"vendor_setting":"private-patch-value","messages":[{"content":"private-conversation"}]}"""
        ).asJsonObject))
        val preview = buildReasoningPreview(provider.copy(useResponseApi = true), "model", ReasoningEffort.HIGH, config, null,
            CustomRequestOverride(headers = mapOf("X-Test" to "private-header-value")),
            custom("""{"reasoning":{"effort":"low","secret":"private-body-value"}}"""), CustomRequestOverride())
        listOf("private-patch-value", "private-header-value", "private-body-value", "private-conversation").forEach {
            assertFalse(preview.parameters.contains(it))
        }
        assertTrue(preview.parameters.contains("low"))
    }

    @Test
    fun modelBudgetRangeWarningPreservesChosenBudget() {
        val metadata = ModelMetadata("model", contextTokens = 100000, reasoningBudgetTokensMax = 8000)
        val preview = buildReasoningPreview(provider.copy(type = ProviderType.GEMINI), "gemini-2.5-pro", ReasoningEffort.HIGH,
            ReasoningConfig(budgets = mapOf(ReasoningEffort.HIGH to 9000)), metadata,
            CustomRequestOverride(), CustomRequestOverride(), CustomRequestOverride())
        assertTrue(ReasoningWarning.BUDGET_OUTSIDE_MODEL_RANGE in preview.warnings)
        assertEquals(9000, JsonParser.parseString(preview.parameters).asJsonObject.getAsJsonObject("generationConfig.thinkingConfig")["thinkingBudget"].asInt)
    }

    @Test
    fun fixedJsonCanUndoOrProvideTheOffParameters() {
        val offConfig = ReasoningConfig(dialect = com.aharou.feature.settings.domain.model.ReasoningDialect.THINKING_TYPE)
        val overridden = buildReasoningPreview(provider, "model", ReasoningEffort.NONE, offConfig, null,
            CustomRequestOverride(), CustomRequestOverride(), custom("""{"thinking":{"type":"enabled"}}"""))
        assertTrue(ReasoningWarning.CANNOT_DISABLE in overridden.warnings)
        val provided = buildReasoningPreview(provider, "model", ReasoningEffort.NONE, ReasoningConfig(), null,
            CustomRequestOverride(), CustomRequestOverride(), custom("""{"thinking":{"type":"disabled"}}"""))
        assertFalse(ReasoningWarning.CANNOT_DISABLE in provided.warnings)
    }

    @Test
    fun chatPreviewUsesMiddlewareModelMapping() {
        val mapped = provider.copy(type = ProviderType.ANTHROPIC, requestMiddleware = """{"modelMap":{"alias":"claude-sonnet-4-6"}}""")
        val preview = buildReasoningPreview(mapped, "alias", ReasoningEffort.HIGH, ReasoningConfig(), null,
            CustomRequestOverride(), CustomRequestOverride(), CustomRequestOverride())
        assertEquals("adaptive", JsonParser.parseString(preview.parameters).asJsonObject.getAsJsonObject("thinking")["type"].asString)
    }
}
