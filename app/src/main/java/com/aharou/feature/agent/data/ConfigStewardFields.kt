package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.ConfigError
import com.aharou.feature.agent.domain.provider.AutoRetryOptions
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.steward.StewardSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能托管（Steward Mode）→ 配置通道。
 *
 * 开关与轮数存在 SharedPreferences（`aharou_steward_prefs`）而不是 DataStore，
 * 所以用 [ClosureField] 桥接；那三个属性都是同步读写，正好对得上。
 */
@Singleton
class ConfigStewardFields @Inject constructor(
    private val steward: StewardSettings,
) {

    fun registerInto(registry: ConfigRegistry) = with(registry) {
        register(
            ClosureField(
                path = "steward.enabled",
                displayName = "智能托管",
                description = "开启后每轮回答结束会旁路判断最初任务是否做完，没做完就把下一步指令续发进同一会话。默认关闭。",
                valueSchema = ConfigSchema.Bool,
                reader = { ConfigValue.Bool(steward.enabled) },
                writer = { v -> steward.enabled = (v as? ConfigValue.Bool)?.value ?: false },
            ),
        )
        register(
            ClosureField(
                path = "steward.max_loops",
                displayName = "托管轮数上限",
                description = "自动续发最多几轮，范围 ${StewardSettings.MIN_LOOPS}..${StewardSettings.MAX_LOOPS}，" +
                    "默认 ${StewardSettings.DEFAULT_MAX_LOOPS}。超过范围会被自动收敛。",
                valueSchema = ConfigSchema.Int(StewardSettings.MIN_LOOPS, StewardSettings.MAX_LOOPS),
                reader = { ConfigValue.Int(steward.maxLoops) },
                writer = { v ->
                    steward.maxLoops = (v as? ConfigValue.Int)?.value ?: StewardSettings.DEFAULT_MAX_LOOPS
                },
            ),
        )
        register(
            ClosureField(
                path = "steward.unlimited",
                displayName = "不限轮数",
                description = "开启后不受上面的轮数上限限制，但仍受控制器里的硬上限与超时约束。",
                valueSchema = ConfigSchema.Bool,
                reader = { ConfigValue.Bool(steward.unlimited) },
                writer = { v -> steward.unlimited = (v as? ConfigValue.Bool)?.value ?: false },
            ),
        )
        fun bool(path: String, name: String, get: (AutoRetryOptions) -> Boolean, set: (AutoRetryOptions, Boolean) -> AutoRetryOptions) {
            register(ClosureField(
                path = "steward.retry_$path", displayName = name, description = name,
                valueSchema = ConfigSchema.Bool,
                reader = { ConfigValue.Bool(get(steward.autoRetryOptions)) },
                writer = { v -> steward.autoRetryOptions = set(steward.autoRetryOptions, (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")) },
            ))
        }
        fun int(path: String, name: String, max: Int, get: (AutoRetryOptions) -> Int, set: (AutoRetryOptions, Int) -> AutoRetryOptions) {
            register(ClosureField(
                path = "steward.retry_$path", displayName = name, description = name,
                valueSchema = ConfigSchema.Int(0, max),
                reader = { ConfigValue.Int(get(steward.autoRetryOptions)) },
                writer = { v -> steward.autoRetryOptions = set(steward.autoRetryOptions, (v as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("integer")) },
            ))
        }
        fun str(path: String, name: String, get: (AutoRetryOptions) -> String, set: (AutoRetryOptions, String) -> AutoRetryOptions) {
            register(ClosureField(
                path = "steward.retry_$path", displayName = name, description = name,
                valueSchema = ConfigSchema.Str(),
                reader = { ConfigValue.Str(get(steward.autoRetryOptions)) },
                writer = { v -> steward.autoRetryOptions = set(steward.autoRetryOptions, (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")) },
            ))
        }
        bool("enabled", "自动重试增强", { it.enabled }) { o, v -> o.copy(enabled = v) }
        bool("jitter", "重试随机抖动", { it.jitter }) { o, v -> o.copy(jitter = v) }
        bool("network", "重试网络错误", { it.retryOnNetworkError }) { o, v -> o.copy(retryOnNetworkError = v) }
        int("max_retries", "额外重试次数", AutoRetryOptions.MAX_MAX_RETRIES, { it.maxRetries }) { o, v -> o.copy(maxRetries = v) }
        int("initial_delay_ms", "首次延迟毫秒", Int.MAX_VALUE, { it.initialDelayMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }) { o, v -> o.copy(initialDelayMs = v.toLong()) }
        int("max_delay_ms", "最大延迟毫秒", Int.MAX_VALUE, { it.maxDelayMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }) { o, v -> o.copy(maxDelayMs = v.toLong()) }
        str("multiplier", "退避倍率", { it.multiplier.toString() }) { o, v -> o.copy(multiplier = v.toDoubleOrNull() ?: throw ConfigError.InvalidValue("退避倍率必须为数字")) }
        str("status_codes", "重试状态码", { AutoRetryOptions.formatStatusCodes(it.retryStatusCodes) }) { o, v -> o.copy(retryStatusCodes = AutoRetryOptions.parseStatusCodes(v)) }
        str("keywords", "重试关键字", { AutoRetryOptions.formatKeywords(it.retryKeywords) }) { o, v -> o.copy(retryKeywords = AutoRetryOptions.parseKeywords(v, emptyList())) }
        str("stop_keywords", "停止关键字", { AutoRetryOptions.formatKeywords(it.stopKeywords) }) { o, v -> o.copy(stopKeywords = AutoRetryOptions.parseKeywords(v, emptyList())) }
    }
}
