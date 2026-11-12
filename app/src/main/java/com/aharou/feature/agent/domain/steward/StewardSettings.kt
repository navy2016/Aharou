package com.aharou.feature.agent.domain.steward

import android.content.Context
import android.content.SharedPreferences
import com.aharou.feature.agent.domain.provider.AutoRetryOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能托管的开关与轮数上限。
 *
 * 存 SharedPreferences 而不是记忆目录：这是本机的运行开关，写进记忆目录会被同步到别的设备上。
 */
@Singleton
class StewardSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 托管总开关。 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 自动续发轮数上限，收敛到 [MIN_LOOPS]..[MAX_LOOPS]。 */
    var maxLoops: Int
        get() = prefs.getInt(KEY_MAX_LOOPS, DEFAULT_MAX_LOOPS).coerceIn(MIN_LOOPS, MAX_LOOPS)
        set(value) = prefs.edit().putInt(KEY_MAX_LOOPS, value.coerceIn(MIN_LOOPS, MAX_LOOPS)).apply()

    /** 不限轮数（仍受控制器里的安全阀约束）。 */
    var unlimited: Boolean
        get() = prefs.getBoolean(KEY_UNLIMITED, false)
        set(value) = prefs.edit().putBoolean(KEY_UNLIMITED, value).apply()

    var autoRetryOptions: AutoRetryOptions
        get() = AutoRetryOptions(
            enabled = prefs.getBoolean(KEY_RETRY_ENABLED, false),
            maxRetries = prefs.getInt(KEY_RETRY_MAX, AutoRetryOptions.DEFAULT_MAX_RETRIES),
            initialDelayMs = prefs.getLong(KEY_RETRY_INITIAL_DELAY, AutoRetryOptions.DEFAULT_INITIAL_DELAY_MS),
            multiplier = prefs.getString(KEY_RETRY_MULTIPLIER, null)?.toDoubleOrNull()
                ?: AutoRetryOptions.DEFAULT_MULTIPLIER,
            maxDelayMs = prefs.getLong(KEY_RETRY_MAX_DELAY, AutoRetryOptions.DEFAULT_MAX_DELAY_MS),
            jitter = prefs.getBoolean(KEY_RETRY_JITTER, true),
            retryOnNetworkError = prefs.getBoolean(KEY_RETRY_NETWORK, true),
            retryStatusCodes = prefs.getString(KEY_RETRY_STATUS_CODES, null)
                ?.let { AutoRetryOptions.parseStatusCodes(it) } ?: AutoRetryOptions.DEFAULT_RETRY_STATUS_CODES,
            retryKeywords = prefs.getString(KEY_RETRY_KEYWORDS, null)
                ?.let { AutoRetryOptions.parseKeywords(it, emptyList()) } ?: AutoRetryOptions.DEFAULT_RETRY_KEYWORDS,
            stopKeywords = prefs.getString(KEY_RETRY_STOP_KEYWORDS, null)
                ?.let { AutoRetryOptions.parseKeywords(it, emptyList()) } ?: AutoRetryOptions.DEFAULT_STOP_KEYWORDS,
        ).normalized()
        set(value) {
            val options = value.normalized()
            prefs.edit()
                .putBoolean(KEY_RETRY_ENABLED, options.enabled)
                .putInt(KEY_RETRY_MAX, options.maxRetries)
                .putLong(KEY_RETRY_INITIAL_DELAY, options.initialDelayMs)
                .putString(KEY_RETRY_MULTIPLIER, options.multiplier.toString())
                .putLong(KEY_RETRY_MAX_DELAY, options.maxDelayMs)
                .putBoolean(KEY_RETRY_JITTER, options.jitter)
                .putBoolean(KEY_RETRY_NETWORK, options.retryOnNetworkError)
                .putString(KEY_RETRY_STATUS_CODES, AutoRetryOptions.formatStatusCodes(options.retryStatusCodes))
                .putString(KEY_RETRY_KEYWORDS, AutoRetryOptions.formatKeywords(options.retryKeywords))
                .putString(KEY_RETRY_STOP_KEYWORDS, AutoRetryOptions.formatKeywords(options.stopKeywords))
                .apply()
            _autoRetryOptions.value = options
        }

    private val _autoRetryOptions = MutableStateFlow(autoRetryOptions)
    val autoRetryOptionsFlow: StateFlow<AutoRetryOptions> = _autoRetryOptions.asStateFlow()
    private val retryPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("retry_")) _autoRetryOptions.value = autoRetryOptions
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(retryPreferenceListener)
    }

    companion object {
        const val DEFAULT_MAX_LOOPS = 5
        const val MIN_LOOPS = 1
        const val MAX_LOOPS = 20

        private const val PREFS = "aharou_steward_prefs"
        private const val KEY_ENABLED = "steward_enabled"
        private const val KEY_MAX_LOOPS = "steward_max_loops"
        private const val KEY_UNLIMITED = "steward_unlimited"
        private const val KEY_RETRY_ENABLED = "retry_enabled"
        private const val KEY_RETRY_MAX = "retry_max"
        private const val KEY_RETRY_INITIAL_DELAY = "retry_initial_delay_ms"
        private const val KEY_RETRY_MULTIPLIER = "retry_multiplier"
        private const val KEY_RETRY_MAX_DELAY = "retry_max_delay_ms"
        private const val KEY_RETRY_JITTER = "retry_jitter"
        private const val KEY_RETRY_NETWORK = "retry_network"
        private const val KEY_RETRY_STATUS_CODES = "retry_status_codes"
        private const val KEY_RETRY_KEYWORDS = "retry_keywords"
        private const val KEY_RETRY_STOP_KEYWORDS = "retry_stop_keywords"
    }
}