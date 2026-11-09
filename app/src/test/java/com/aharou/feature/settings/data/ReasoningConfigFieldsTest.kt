package com.aharou.feature.settings.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.data.local.CustomModelMetadataStore
import com.aharou.feature.settings.data.local.CustomRequestStore
import com.aharou.feature.settings.data.local.ReasoningConfigStore
import com.aharou.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ReasoningConfigFieldsTest {
    private lateinit var context: Context
    private lateinit var store: ReasoningConfigStore
    private lateinit var field: ConfigField
    private val config = ReasoningConfig(
        effortValues = mapOf(ReasoningEffort.NONE to "off"),
        patches = mapOf(ReasoningEffort.HIGH to JsonParser.parseString("""{"reasoning":null}""").asJsonObject),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("aharou_reasoning_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("model_reasoning_effort_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        mockkObject(KeystoreCipher)
        every { KeystoreCipher.encryptString(any()) } answers { firstArg<String>() }
        every { KeystoreCipher.decryptString(any()) } answers { firstArg<String>() }
        store = ReasoningConfigStore(context)
        field = reasoningConfigField(
            "providers.provider.models.model.reasoning_config",
            { store.getModel("provider", "model") },
            { store.setModel("provider", "model", it) },
        )
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("aharou_reasoning_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("model_reasoning_effort_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        unmockkObject(KeystoreCipher)
    }

    @Test
    fun sensitiveFieldAcceptsJsonStringAndReadsThroughSharedCodec() {
        assertEquals(ConfigRisk.SENSITIVE, field.risk)
        assertEquals(ConfigSchema.Json, field.valueSchema)
        assertTrue(field.revertable)
        val value = ConfigValue.Str(ReasoningConfigCodec.encode(config).toString())

        field.write(value)

        assertEquals(config, store.getModel("provider", "model"))
        assertEquals(value, field.read())
    }

    @Test
    fun jsonObjectInputPreservesExplicitNullAndEmptyObjectClearsConfig() {
        val value = ConfigValue.decode("""{"patches":{"none":{"thinking":null}}}""") as ConfigValue.Obj
        field.write(value)
        assertTrue(store.getModel("provider", "model").patches.getValue(ReasoningEffort.NONE).get("thinking").isJsonNull)

        field.write(ConfigValue.Obj(emptyMap()))

        assertEquals(ReasoningConfig(), store.getModel("provider", "model"))
        assertTrue(context.getSharedPreferences("aharou_reasoning_config", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun invalidJsonAndCodecFailuresBecomeInvalidValueWithoutChangingStorage() {
        store.setModel("provider", "model", config)
        listOf(
            "{", "[]", "null", "true", "\"string\"",
            """{"dialect":"unknown"}""",
            """{"unknown":{}}""",
            """{"effortValues":{"default":"low"}}""",
            """{"budgets":{"none":1}}""",
            """{"budgets":{"high":1.5}}""",
            """{"budgets":{"high":${Int.MAX_VALUE}}}""",
            """{"patches":{"high":null}}""",
        ).forEach { raw ->
            assertThrows(raw, ConfigError.InvalidValue::class.java) { field.write(ConfigValue.Str(raw)) }
            assertEquals(config, store.getModel("provider", "model"))
        }
        assertThrows(ConfigError.InvalidValue::class.java) {
            field.write(ConfigValue.Obj(mapOf("dialect" to ConfigValue.Bool(true))))
        }
        assertThrows(ConfigError.TypeMismatch::class.java) { field.write(ConfigValue.Int(1)) }
    }

    @Test
    fun providerRegistrationKeepsDefaultEffortIndependentAndDropsDeletedModelField() {
        var provider: AIProviderConfig? = AIProviderConfig(
            id = "reasoning-fields",
            name = "Provider",
            type = ProviderType.OPENAI,
            apiKey = "",
            baseUrl = "",
            defaultModel = "model-2.5:latest",
            models = listOf("model-2.5:latest"),
        )
        val providers = mockk<AIProviderRepository>()
        coEvery { providers.getProviderById(any()) } answers {
            provider?.takeIf { it.id == firstArg<String>() }
        }
        val defaultEffort = ModelReasoningEffortRepository(context)
        defaultEffort.set("reasoning-fields", "model-2.5:latest", ReasoningEffort.XHIGH.name)
        val registry = ConfigRegistry.init(context)
        ConfigProviderFields(
            context = context,
            modelMetadataStore = CustomModelMetadataStore(context),
            customRequestStore = CustomRequestStore(context),
            reasoningConfigStore = store,
            reasoningStore = defaultEffort,
            providers = providers,
        ).registerInto(registry)
        val basePath = "providers.reasoning-fields.models.model-2.5:latest"
        val configField = registry.resolveField("$basePath.reasoning_config")!!
        val effortField = registry.resolveField("$basePath.reasoning_effort")!!

        configField.write(ConfigValue.Str(ReasoningConfigCodec.encode(config).toString()))

        assertEquals(config, store.getModel("reasoning-fields", "model-2.5:latest"))
        assertEquals(ConfigValue.Str("xhigh"), effortField.read())
        effortField.write(ConfigValue.Str("max"))
        assertEquals(ReasoningEffort.MAX.name, defaultEffort.get("reasoning-fields", "model-2.5:latest"))
        assertEquals(config, store.getModel("reasoning-fields", "model-2.5:latest"))
        provider = provider?.copy(models = emptyList())
        assertNull(registry.resolveField("$basePath.reasoning_config"))
        provider = null
        assertNull(registry.resolveField("$basePath.reasoning_config"))
    }
}
