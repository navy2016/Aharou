package com.aharou.feature.settings.data

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigValue
import com.aharou.feature.voice.data.CallBackgroundRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ConfigVoiceFieldsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val background = mockk<CallBackgroundRepository>()
    private val config = MutableStateFlow(CallBackgroundRepository.Config("old.img", 5, 0.2f))
    private lateinit var registry: ConfigRegistry

    @Before
    fun setUp() {
        every { background.config } returns config
        every { background.dir() } returns temporary.root
        coEvery { background.update(any()) } answers { config.value = firstArg() }
        coEvery { background.clearImage() } answers { config.value = config.value.copy(imagePath = null) }
        coEvery { background.saveImage(any()) } returns "new.img"
        registry = ConfigRegistry::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        ConfigVoiceFields(
            ttsSettings = mockk(relaxed = true), sttSettings = mockk(relaxed = true),
            wakeSettings = mockk(relaxed = true), overlaySettings = mockk(relaxed = true),
            callBackground = background, context = mockk<Context>(relaxed = true)
        ).registerInto(registry)
    }

    @Test
    fun blurAndDimWritesPreserveTheOtherBackgroundValues() {
        registry.resolveField("voice.call_background_blur_dp")!!.write(ConfigValue.Int(18))
        assertEquals(CallBackgroundRepository.Config("old.img", 18, 0.2f), config.value)
        registry.resolveField("voice.call_background_dim")!!.write(ConfigValue.Double(0.4))
        assertEquals(CallBackgroundRepository.Config("old.img", 18, 0.4f), config.value)
    }

    @Test
    fun numericLimitsAreValidatedBeforeWriting() {
        assertThrows(ConfigError.OutOfRange::class.java) {
            registry.resolveField("voice.call_background_blur_dp")!!.write(ConfigValue.Int(41))
        }
        assertThrows(ConfigError.OutOfRange::class.java) {
            registry.resolveField("voice.call_background_dim")!!.write(ConfigValue.Double(-0.1))
        }
        assertEquals(CallBackgroundRepository.Config("old.img", 5, 0.2f), config.value)
    }

    @Test
    fun imageImportAndClearUseTheSameRepositoryAsTheUi() {
        val field = registry.resolveField("voice.call_background_image")!!
        assertFalse(field.revertable)
        field.write(ConfigValue.Str("content://images/background"))
        assertEquals(CallBackgroundRepository.Config("new.img", 5, 0.2f), config.value)
        field.write(ConfigValue.Str(""))
        assertEquals(CallBackgroundRepository.Config(null, 5, 0.2f), config.value)
        coVerify(exactly = 1) { background.saveImage(any()) }
        coVerify(exactly = 1) { background.clearImage() }
    }

    @Test
    fun imageFieldRejectsNetworkUris() {
        assertThrows(ConfigError.InvalidValue::class.java) {
            registry.resolveField("voice.call_background_image")!!.write(ConfigValue.Str("https://example.com/image"))
        }
        coVerify(exactly = 0) { background.saveImage(any()) }
        assertEquals("old.img", config.value.imagePath)
    }
}
