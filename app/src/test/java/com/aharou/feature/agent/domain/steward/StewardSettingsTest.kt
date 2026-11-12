package com.aharou.feature.agent.domain.steward

import android.app.Application
import android.content.Context
import android.os.Looper
import com.aharou.feature.agent.domain.provider.AutoRetryOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class StewardSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun resetPreferences() {
        context.getSharedPreferences("aharou_steward_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun retryStateIsIndependentOfStewardEnabledState() {
        val settings = StewardSettings(context)
        assertFalse(settings.autoRetryOptionsFlow.value.enabled)
        settings.autoRetryOptions = AutoRetryOptions(enabled = true, maxRetries = 100)
        assertTrue(settings.autoRetryOptionsFlow.value.enabled)
        assertFalse(settings.enabled)
        settings.enabled = true
        settings.autoRetryOptions = settings.autoRetryOptions.copy(enabled = false)
        assertTrue(settings.enabled)
        assertFalse(settings.autoRetryOptionsFlow.value.enabled)
        assertEquals(100, settings.autoRetryOptionsFlow.value.maxRetries)
    }

    @Test fun externalPreferenceWritesRefreshStateWithoutOpeningSheet() {
        val settings = StewardSettings(context)
        context.getSharedPreferences("aharou_steward_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("retry_enabled", true).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(settings.autoRetryOptionsFlow.value.enabled)
        context.getSharedPreferences("aharou_steward_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("retry_enabled", false).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(settings.autoRetryOptionsFlow.value.enabled)
    }
}
