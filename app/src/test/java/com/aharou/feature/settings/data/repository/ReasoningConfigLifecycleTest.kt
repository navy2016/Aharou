package com.aharou.feature.settings.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.data.local.CustomRequestStore
import com.aharou.feature.settings.data.local.ReasoningConfigStore
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ReasoningConfig
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ReasoningConfigLifecycleTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase
    private lateinit var store: ReasoningConfigStore
    private lateinit var repository: AIProviderRepositoryImpl
    private val provider = AIProviderConfig(
        id = "provider",
        name = "Provider",
        type = ProviderType.OPENAI,
        apiKey = "",
        baseUrl = "",
        defaultModel = "kept",
        models = listOf("old", "removed", "kept"),
    )
    private val config = ReasoningConfig(
        patches = mapOf(ReasoningEffort.NONE to JsonParser.parseString("""{"thinking":null}""").asJsonObject),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("aharou_reasoning_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("aharou_custom_request", Context.MODE_PRIVATE).edit().clear().commit()
        mockkObject(KeystoreCipher)
        every { KeystoreCipher.encryptString(any()) } answers { firstArg<String>() }
        every { KeystoreCipher.decryptString(any()) } answers { firstArg<String>() }
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        store = ReasoningConfigStore(context)
        repository = AIProviderRepositoryImpl(database.aiProviderDao(), database, CustomRequestStore(context), store)
    }

    @After
    fun tearDown() {
        database.close()
        context.getSharedPreferences("aharou_reasoning_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("aharou_custom_request", Context.MODE_PRIVATE).edit().clear().commit()
        unmockkObject(KeystoreCipher)
    }

    @Test
    fun saveProviderCleansRemovedModelsAfterUiMovesRenamedConfig() = runTest {
        repository.saveProvider(provider)
        provider.models.forEach { store.setModel(provider.id, it, config) }
        store.setModel("other-provider", "old", config)
        store.moveModel(provider.id, "old", "renamed")

        repository.saveProvider(provider.copy(models = listOf("renamed", "kept")))
        repository.updateModels(provider.id, listOf("old", "removed", "renamed", "kept"))

        assertEquals(ReasoningConfig(), store.getModel(provider.id, "old"))
        assertEquals(ReasoningConfig(), store.getModel(provider.id, "removed"))
        assertEquals(config, store.getModel(provider.id, "renamed"))
        assertEquals(config, store.getModel(provider.id, "kept"))
        assertEquals(config, store.getModel("other-provider", "old"))
    }

    @Test
    fun updateModelsCleansRemovedModelsWithoutRestoringThemOnReadd() = runTest {
        repository.saveProvider(provider)
        provider.models.forEach { store.setModel(provider.id, it, config) }
        store.setModel("other-provider", "old", config)

        repository.updateModels(provider.id, listOf("kept"))
        repository.updateModels(provider.id, provider.models)

        assertEquals(ReasoningConfig(), store.getModel(provider.id, "old"))
        assertEquals(ReasoningConfig(), store.getModel(provider.id, "removed"))
        assertEquals(config, store.getModel(provider.id, "kept"))
        assertEquals(config, store.getModel("other-provider", "old"))
    }

    @Test
    fun deletingProviderAlsoClearsConfigsNotInItsCurrentModelList() = runTest {
        repository.saveProvider(provider)
        store.setModel(provider.id, "old", config)
        store.setModel(provider.id, "orphan", config)
        store.setModel("other-provider", "old", config)

        repository.deleteProvider(provider.id)
        repository.saveProvider(provider)

        assertEquals(ReasoningConfig(), store.getModel(provider.id, "old"))
        assertEquals(ReasoningConfig(), store.getModel(provider.id, "orphan"))
        assertEquals(config, store.getModel("other-provider", "old"))
    }

    @Test
    fun batchDeleteOnlyClearsTargetProviders() = runTest {
        listOf("provider", "second", "kept-provider").forEach { id ->
            repository.saveProvider(provider.copy(id = id))
            store.setModel(id, "old", config)
            store.setModel(id, "orphan", config)
        }

        repository.deleteProviders(emptyList())
        assertEquals(config, store.getModel("provider", "old"))
        repository.deleteProviders(listOf("provider", "second"))

        listOf("provider", "second").forEach { id ->
            assertEquals(ReasoningConfig(), store.getModel(id, "old"))
            assertEquals(ReasoningConfig(), store.getModel(id, "orphan"))
        }
        assertEquals(config, store.getModel("kept-provider", "old"))
        assertEquals(config, store.getModel("kept-provider", "orphan"))
    }
}
