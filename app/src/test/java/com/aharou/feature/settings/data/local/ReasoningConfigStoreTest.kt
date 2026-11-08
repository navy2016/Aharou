package com.aharou.feature.settings.data.local

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.aharou.feature.settings.domain.model.ReasoningDialect
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ReasoningConfigStoreTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var store: ReasoningConfigStore
    private val prefix = "AICODEENC1:"
    private val config = ReasoningConfig(
        patches = mapOf(ReasoningEffort.NONE to JsonParser.parseString(
            """{"thinking":null,"extra":{"token":"unit-test-marker"}}"""
        ).asJsonObject),
    )
    private val otherConfig = ReasoningConfig(effortValues = mapOf(ReasoningEffort.HIGH to "high"))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("aharou_reasoning_config", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        mockkObject(KeystoreCipher)
        every { KeystoreCipher.encryptString(any()) } answers {
            prefix + Base64.getEncoder().encodeToString(firstArg<String>().toByteArray())
        }
        every { KeystoreCipher.decryptString(any()) } answers {
            val value = firstArg<String>()
            if (value.startsWith(prefix)) String(Base64.getDecoder().decode(value.removePrefix(prefix))) else value
        }
        store = ReasoningConfigStore(context)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        unmockkObject(KeystoreCipher)
    }

    @Test
    fun persistsEncryptedJsonAcrossInstancesAndPreservesExplicitNull() {
        store.setModel("provider", "model-2.5:latest", config)

        val stored = prefs.all.values.single() as String
        assertTrue(stored.startsWith(prefix))
        assertFalse(stored.contains("unit-test-marker"))
        val restored = ReasoningConfigStore(context).getModel("provider", "model-2.5:latest")
        assertEquals(config, restored)
        assertTrue(restored.patches.getValue(ReasoningEffort.NONE).get("thinking").isJsonNull)
        verify(exactly = 1) { KeystoreCipher.encryptString(any()) }
    }

    @Test
    fun providerAndModelNamesWithDelimitersRemainIsolated() {
        store.setModel("provider", "same-model", config)
        store.setModel("other-provider", "same-model", otherConfig)
        store.setModel("provider", "variant:model", config)
        store.setModel("provider:variant", "model", otherConfig)

        assertEquals(4, prefs.all.size)
        assertEquals(config, store.getModel("provider", "same-model"))
        assertEquals(otherConfig, store.getModel("other-provider", "same-model"))
        assertEquals(config, store.getModel("provider", "variant:model"))
        assertEquals(otherConfig, store.getModel("provider:variant", "model"))
    }

    @Test
    fun removingModelKeepsOtherModelsAndProviders() {
        store.setModel("provider", "removed", config)
        store.setModel("provider", "kept", otherConfig)
        store.setModel("other-provider", "removed", config)

        store.removeModel("provider", "removed")
        store.removeModel("provider", "removed")

        assertEquals(ReasoningConfig(), store.getModel("provider", "removed"))
        assertEquals(otherConfig, store.getModel("provider", "kept"))
        assertEquals(config, store.getModel("other-provider", "removed"))
        assertEquals(2, prefs.all.size)
    }

    @Test
    fun removingProviderKeepsPrefixAndDelimiterNeighbors() {
        store.setModel("provider", "one", config)
        store.setModel("provider", "two", otherConfig)
        store.setModel("provider-long", "one", config)
        store.setModel("provider:child", "one", otherConfig)

        store.removeProvider("provider")
        store.removeProvider("missing")

        assertEquals(ReasoningConfig(), store.getModel("provider", "one"))
        assertEquals(ReasoningConfig(), store.getModel("provider", "two"))
        assertEquals(config, store.getModel("provider-long", "one"))
        assertEquals(otherConfig, store.getModel("provider:child", "one"))
        assertEquals(2, prefs.all.size)
    }

    @Test
    fun emptyAutomaticConfigRemovesOnlyItsKey() {
        store.setModel("provider", "cleared", config)
        store.setModel("provider", "kept", otherConfig)

        store.setModel("provider", "cleared", ReasoningConfig())
        store.setModel("missing", "model", ReasoningConfig())

        assertEquals(1, prefs.all.size)
        assertEquals(ReasoningConfig(), store.getModel("provider", "cleared"))
        assertEquals(otherConfig, store.getModel("provider", "kept"))
        verify(exactly = 2) { KeystoreCipher.encryptString(any()) }
    }

    @Test
    fun autoOverridesAndExplicitDialectsAreNotRemovedAsAutomatic() {
        listOf(
            ReasoningConfig(effortValues = mapOf(ReasoningEffort.NONE to "off")),
            ReasoningConfig(budgets = mapOf(ReasoningEffort.LOW to 1)),
            ReasoningConfig(patches = mapOf(ReasoningEffort.HIGH to JsonObject())),
            ReasoningConfig(dialect = ReasoningDialect.CUSTOM),
        ).forEach { value ->
            store.setModel("provider", "model", value)
            assertEquals(1, prefs.all.size)
            assertEquals(value, ReasoningConfigStore(context).getModel("provider", "model"))
        }
    }

    @Test
    fun movingModelTransfersCiphertextAndReplacesOnlyItsTarget() {
        store.setModel("provider", "old", config)
        val ciphertext = prefs.all.values.single()
        store.setModel("provider", "new", otherConfig)
        store.setModel("other-provider", "old", otherConfig)

        store.moveModel("provider", "old", "new")

        verify(exactly = 3) { KeystoreCipher.encryptString(any()) }
        verify(exactly = 0) { KeystoreCipher.decryptString(any()) }
        assertEquals(2, prefs.all.size)
        assertTrue(ciphertext in prefs.all.values)
        assertEquals(ReasoningConfig(), store.getModel("provider", "old"))
        assertEquals(config, store.getModel("provider", "new"))
        assertEquals(otherConfig, store.getModel("other-provider", "old"))
    }

    @Test
    fun missingSourceAndSameNameMovesKeepTheTargetUntouched() {
        store.setModel("provider", "new", config)
        val before = prefs.all

        store.moveModel("provider", "missing", "new")
        store.moveModel("provider", "new", "new")

        assertEquals(before, prefs.all)
        assertEquals(config, store.getModel("provider", "new"))
    }

    @Test
    fun invalidWritesNeverReplaceStoredConfig() {
        store.setModel("provider", "model", config)
        val before = prefs.all

        assertThrows(IllegalArgumentException::class.java) {
            store.setModel("provider", "model", ReasoningConfig(budgets = mapOf(ReasoningEffort.NONE to 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.setModel("provider", "model", ReasoningConfig(effortValues = mapOf(ReasoningEffort.DEFAULT to "low")))
        }

        assertEquals(before, prefs.all)
        assertEquals(config, store.getModel("provider", "model"))
    }

    @Test
    fun malformedStoredConfigIsNotSilentlyAccepted() {
        store.setModel("provider", "model", config)
        val key = prefs.all.keys.single()
        listOf("{", "[]", """{"dialect":"unknown"}""", """{"patches":{"none":null}}""").forEach { raw ->
            prefs.edit().putString(key, KeystoreCipher.encryptString(raw)).commit()
            assertThrows(IllegalArgumentException::class.java) { store.getModel("provider", "model") }
        }
    }
}
