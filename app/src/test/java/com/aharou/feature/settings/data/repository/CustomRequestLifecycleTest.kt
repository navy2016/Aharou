package com.aharou.feature.settings.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.settings.data.local.CustomRequestOverride
import com.aharou.feature.settings.data.local.CustomRequestStore
import com.aharou.feature.settings.data.local.ReasoningConfigStore
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
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
class CustomRequestLifecycleTest {
    private lateinit var database: AgentDatabase
    private lateinit var store: CustomRequestStore
    private lateinit var repository: AIProviderRepositoryImpl
    private val provider = AIProviderConfig(
        id = "provider",
        name = "Provider",
        type = ProviderType.OPENAI,
        apiKey = "",
        baseUrl = "",
        defaultModel = "kept",
        models = listOf("old", "kept"),
    )
    private val request = CustomRequestOverride(
        headers = mapOf("X-Test" to "value"),
        body = JsonParser.parseString("""{"extra":null}""").asJsonObject,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("aharou_custom_request", Context.MODE_PRIVATE).edit().clear().commit()
        mockkObject(KeystoreCipher)
        every { KeystoreCipher.encryptString(any()) } answers { firstArg<String>() }
        every { KeystoreCipher.decryptString(any()) } answers { firstArg<String>() }
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        store = CustomRequestStore(context)
        repository = AIProviderRepositoryImpl(database.aiProviderDao(), database, store, ReasoningConfigStore(context))
    }

    @After
    fun tearDown() {
        database.close()
        unmockkObject(KeystoreCipher)
    }

    @Test
    fun renamingModelRemovesOnlyOldOverrideAndReaddingDoesNotRestoreIt() = runTest {
        repository.saveProvider(provider)
        store.setModel(provider.id, "old", request)
        store.setModel(provider.id, "kept", request)
        store.setModel(provider.id, "renamed", request)
        store.setModel("other-provider", "old", request)

        repository.saveProvider(provider.copy(models = listOf("renamed", "kept")))
        repository.updateModels(provider.id, listOf("old", "renamed", "kept"))

        assertEquals(CustomRequestOverride(), store.getModel(provider.id, "old"))
        assertEquals(request, store.getModel(provider.id, "renamed"))
        assertEquals(request, store.getModel(provider.id, "kept"))
        assertEquals(request, store.getModel("other-provider", "old"))
    }

    @Test
    fun modelListUpdateClearsRemovedOverrides() = runTest {
        repository.saveProvider(provider)
        store.setModel(provider.id, "old", request)
        store.setModel(provider.id, "kept", request)
        store.setProvider(provider.id, request)

        repository.updateModels(provider.id, listOf("kept"))

        assertEquals(CustomRequestOverride(), store.getModel(provider.id, "old"))
        assertEquals(request, store.getModel(provider.id, "kept"))
        assertEquals(request, store.getProvider(provider.id))
    }
}
