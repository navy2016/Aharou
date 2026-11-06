package com.aharou.core.config

/** 展示面上敏感值的统一占位文本。 */
const val CONFIG_MASK_TEXT = "••• (hidden)"

/**
 * 敏感字段在展示面（确认弹窗、审计页）的打码。
 *
 * **只用于显示**：审计表里存的仍是真值，因为撤销（[com.aharou.core.config.audit.ConfigRevert]）
 * 要读旧值回写；在存储层调用它会让撤销把占位文本写回真实设置。
 */
fun ConfigValue.maskedForDisplay(sensitive: Boolean): ConfigValue =
    if (sensitive) ConfigValue.Str(CONFIG_MASK_TEXT) else this

private val customRequestPath = Regex(
    """^(custom_request\.global|providers\.[^.]+(?:\.models\..+)?\.custom_request)\.(headers|body)$"""
)

internal fun isCustomRequestPath(path: String): Boolean = customRequestPath.matches(path)

/** 自定义请求在模型或供应商删除后仍包含敏感数据，不能依赖动态字段是否存在。 */
fun isSensitivePath(path: String): Boolean =
    isCustomRequestPath(path) || runCatching { ConfigRegistry.get().resolveField(path)?.risk }
        .getOrNull() == ConfigRisk.SENSITIVE
