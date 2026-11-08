package com.aharou.feature.settings.data

import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.aharou.feature.settings.domain.model.ReasoningDialect
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningConfigCodecTest {
    private fun obj(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject

    @Test
    fun onlyEmptyAutoConfigIsAutomatic() {
        assertEquals(ReasoningConfig(), ReasoningConfigCodec.decode(obj("{}")))
        assertTrue(ReasoningConfigCodec.decode(ReasoningConfigCodec.encode(ReasoningConfig())).isAutomatic)
        listOf(
            ReasoningConfig(effortValues = mapOf(ReasoningEffort.NONE to "off")),
            ReasoningConfig(budgets = mapOf(ReasoningEffort.LOW to 1)),
            ReasoningConfig(patches = mapOf(ReasoningEffort.HIGH to obj("{}"))),
            ReasoningConfig(dialect = ReasoningDialect.CUSTOM),
        ).forEach { config ->
            assertFalse(config.isAutomatic)
            assertEquals(config, ReasoningConfigCodec.decode(ReasoningConfigCodec.encode(config)))
        }
    }

    @Test
    fun everyDialectAndNonDefaultEffortRoundTripsWithExplicitNull() {
        val efforts = ReasoningEffort.entries.filter { it != ReasoningEffort.DEFAULT }
        ReasoningDialect.entries.forEach { dialect ->
            val config = ReasoningConfig(
                dialect = dialect,
                effortValues = efforts.associateWith { "provider-${it.name.lowercase()}" },
                budgets = mapOf(ReasoningEffort.LOW to 1, ReasoningEffort.MAX to Int.MAX_VALUE - 4096),
                patches = efforts.associateWith {
                    obj("""{"thinking":null,"nested":{"value":null},"items":[null,1]}""")
                },
            )
            val encoded = ReasoningConfigCodec.encode(config)
            assertEquals(dialect.name.lowercase(), encoded.get("dialect").asString)
            assertEquals(efforts.map { it.name.lowercase() }.toSet(), encoded.getAsJsonObject("effortValues").keySet())
            val decoded = ReasoningConfigCodec.decode(obj(encoded.toString()))
            assertEquals(config, decoded)
            assertTrue(decoded.patches.getValue(ReasoningEffort.NONE).get("thinking").isJsonNull)
        }
    }

    @Test
    fun enumNamesAreCaseInsensitiveAndBudgetsAreExactIntegers() {
        val config = ReasoningConfigCodec.decode(obj(
            """{"dialect":"ANTHROPIC_BUDGET","effortValues":{"NONE":"off"},"budgets":{"LOW":1.0,"HIGH":1e3}}"""
        ))
        assertEquals(ReasoningDialect.ANTHROPIC_BUDGET, config.dialect)
        assertEquals(mapOf(ReasoningEffort.NONE to "off"), config.effortValues)
        assertEquals(mapOf(ReasoningEffort.LOW to 1, ReasoningEffort.HIGH to 1000), config.budgets)
    }

    @Test
    fun unknownFieldsAndEnumNamesAreRejected() {
        listOf(
            """{"unknown":{}}""",
            """{"defaultEffort":"low"}""",
            """{"dialect":"unknown"}""",
            """{"dialect":" auto "}""",
            """{"effortValues":{"unknown":"value"}}""",
            """{"budgets":{"unknown":1}}""",
            """{"patches":{"unknown":{}}}""",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { ReasoningConfigCodec.decode(obj(raw)) }
        }
    }

    @Test
    fun invalidFieldAndMappingTypesAreRejectedRatherThanCoerced() {
        listOf("null", "true", "1", "{}", "[]").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ReasoningConfigCodec.decode(obj("""{"dialect":$value}"""))
            }
            assertThrows(IllegalArgumentException::class.java) {
                ReasoningConfigCodec.decode(obj("""{"effortValues":{"low":$value}}"""))
            }
        }
        listOf("effortValues", "budgets", "patches").forEach { field ->
            listOf("null", "false", "1", "\"value\"", "[]").forEach { value ->
                assertThrows(IllegalArgumentException::class.java) {
                    ReasoningConfigCodec.decode(obj("""{"$field":$value}"""))
                }
            }
        }
        listOf("null", "false", "1", "\"value\"", "[]").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ReasoningConfigCodec.decode(obj("""{"patches":{"none":$value}}"""))
            }
        }
    }

    @Test
    fun defaultMappingsAndNoneBudgetsAreRejected() {
        listOf(
            """{"effortValues":{"default":"low"}}""",
            """{"budgets":{"DEFAULT":1}}""",
            """{"patches":{"default":{}}}""",
            """{"budgets":{"none":1}}""",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { ReasoningConfigCodec.decode(obj(raw)) }
        }
    }

    @Test
    fun invalidBudgetsNeverTruncateOrOverflow() {
        listOf(
            "0", "-1", "${Int.MAX_VALUE - 4095}", "${Int.MAX_VALUE}", "2147483648",
            "1e100", "1.5", "0.99999999999999999999", "\"1\"", "true", "null", "{}", "[]",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                ReasoningConfigCodec.decode(obj("""{"budgets":{"high":$value}}"""))
            }
        }
    }

    @Test
    fun caseInsensitiveDuplicateEffortsAreRejected() {
        listOf(
            """{"effortValues":{"low":"one","LOW":"two"}}""",
            """{"budgets":{"low":1,"LOW":2}}""",
            """{"patches":{"low":{},"LOW":{}}}""",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { ReasoningConfigCodec.decode(obj(raw)) }
        }
    }

    @Test
    fun encodeRejectsInvalidInMemoryConfigs() {
        listOf(
            ReasoningConfig(effortValues = mapOf(ReasoningEffort.DEFAULT to "low")),
            ReasoningConfig(budgets = mapOf(ReasoningEffort.DEFAULT to 1)),
            ReasoningConfig(patches = mapOf(ReasoningEffort.DEFAULT to obj("{}"))),
            ReasoningConfig(budgets = mapOf(ReasoningEffort.NONE to 1)),
            ReasoningConfig(budgets = mapOf(ReasoningEffort.LOW to 0)),
            ReasoningConfig(budgets = mapOf(ReasoningEffort.HIGH to -1)),
            ReasoningConfig(budgets = mapOf(ReasoningEffort.MAX to Int.MAX_VALUE - 4095)),
        ).forEach { config ->
            assertThrows(IllegalArgumentException::class.java) { ReasoningConfigCodec.encode(config) }
        }
    }

    @Test
    fun encodedAndDecodedPatchesDoNotShareMutableJson() {
        val patch = obj("""{"nested":{"value":null}}""")
        val config = ReasoningConfig(patches = mapOf(ReasoningEffort.HIGH to patch))
        val encoded = ReasoningConfigCodec.encode(config)
        val decoded = ReasoningConfigCodec.decode(encoded)
        encoded.getAsJsonObject("patches").getAsJsonObject("high")
            .getAsJsonObject("nested").addProperty("value", "changed")
        assertTrue(patch.getAsJsonObject("nested").get("value").isJsonNull)
        assertEquals(config, decoded)
        decoded.patches.getValue(ReasoningEffort.HIGH).addProperty("extra", true)
        assertFalse(patch.has("extra"))
    }
}
