package com.aharou.feature.agent.domain.subagent

import android.app.Application
import android.content.Context
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigValue
import com.aharou.feature.agent.data.ConfigSubAgentFields
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SubAgentSettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aharou_subagent_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun zeroPersistsAndPositiveLimitsCanBeRestored() {
        val settings = SubAgentSettings(context)
        assertEquals(5, settings.maxRunning())
        settings.setMaxRunning(0)
        assertEquals(0, settings.maxRunningFlow.value)
        assertEquals(0, SubAgentSettings(context).maxRunning())
        settings.setMaxRunning(15)
        assertEquals(15, settings.maxRunning())
        settings.setMaxRunning(16)
        assertEquals(15, settings.maxRunning())
        settings.setMaxRunning(-1)
        assertEquals(0, settings.maxRunning())
    }

    @Test
    fun configChannelAcceptsZeroAndPreservesItOnRead() {
        val settings = SubAgentSettings(context)
        val fields = mutableListOf<ConfigField>()
        val registry = mockk<ConfigRegistry>()
        every { registry.register(any<ConfigField>()) } answers { fields.add(firstArg()); Unit }
        ConfigSubAgentFields(settings).registerInto(registry)
        val field = fields.single { it.path == "subagent.max_running" }
        field.write(ConfigValue.Int(0))
        assertEquals(ConfigValue.Int(0), field.read())
        assertEquals(0, SubAgentSettings(context).maxRunning())
        assertTrue(field.description.contains("0"))
        field.write(ConfigValue.Int(5))
        assertEquals(5, settings.maxRunning())
    }
}
