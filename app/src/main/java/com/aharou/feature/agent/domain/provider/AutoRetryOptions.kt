package com.aharou.feature.agent.domain.provider

/**
 * 智能托管里的增强重试策略。maxRetries 表示首次请求之外的额外次数。
 */
data class AutoRetryOptions(
    val enabled: Boolean = false,
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    val multiplier: Double = DEFAULT_MULTIPLIER,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val jitter: Boolean = true,
    val retryOnNetworkError: Boolean = true,
    val retryStatusCodes: Set<Int> = DEFAULT_RETRY_STATUS_CODES,
    val retryKeywords: List<String> = DEFAULT_RETRY_KEYWORDS,
    val stopKeywords: List<String> = DEFAULT_STOP_KEYWORDS,
) {
    fun normalized(): AutoRetryOptions = copy(
        maxRetries = maxRetries.coerceIn(MIN_MAX_RETRIES, MAX_MAX_RETRIES),
        initialDelayMs = initialDelayMs.coerceAtLeast(0L),
        multiplier = multiplier.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(MAX_MULTIPLIER)
            ?: DEFAULT_MULTIPLIER,
        maxDelayMs = maxDelayMs.coerceAtLeast(0L),
        retryStatusCodes = retryStatusCodes.filter { it in 100..599 }.toSet(),
        retryKeywords = retryKeywords.map(String::trim).filter(String::isNotEmpty).distinct(),
        stopKeywords = stopKeywords.map(String::trim).filter(String::isNotEmpty).distinct(),
    )

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val MIN_MAX_RETRIES = 0
        const val MAX_MAX_RETRIES = 100
        const val DEFAULT_INITIAL_DELAY_MS = 1_000L
        const val DEFAULT_MULTIPLIER = 2.0
        const val MAX_MULTIPLIER = 100.0
        const val DEFAULT_MAX_DELAY_MS = 30_000L
        val DEFAULT_RETRY_STATUS_CODES: Set<Int> = setOf(408, 425, 429, 500, 502, 503, 504, 529)
        val DEFAULT_RETRY_KEYWORDS: List<String> = listOf(
            "并发", "稍后", "重试", "访问量过大", "繁忙", "限流",
            "rate limit", "too many requests", "overloaded", "try again", "timeout", "超时"
        )
        val DEFAULT_STOP_KEYWORDS: List<String> = listOf(
            "余额", "不足", "额度", "欠费", "balance", "insufficient", "quota",
            "invalid api key", "unauthorized", "permission", "未实名"
        )

        fun parseKeywords(raw: String, fallback: List<String>): List<String> =
            raw.split('\n', ',', '，').map(String::trim).filter(String::isNotEmpty).ifEmpty { fallback }

        fun parseStatusCodes(raw: String): Set<Int> =
            raw.split(',', '，', '\n').mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 100..599 }.toSet()

        fun formatKeywords(values: List<String>): String = values.joinToString("\n")
        fun formatStatusCodes(values: Set<Int>): String = values.sorted().joinToString(",")
    }
}
