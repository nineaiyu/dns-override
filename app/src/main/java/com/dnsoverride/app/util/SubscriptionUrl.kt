package com.dnsoverride.app.util

/**
 * 订阅 URL 校验。
 *
 * 为什么要单独收口：订阅地址由用户自由输入，直接丢给 OkHttp 会带来两类问题：
 * - 非法字符串让 `Request.Builder().url()` 抛 `IllegalArgumentException`，导致崩溃；
 * - 形如 `file://` 的协议会被 OkHttp 拒绝前先进入我们的逻辑，扩大非预期输入面。
 *
 * 这里只做**轻量语法校验**（协议 + host），完整解析仍交给 OkHttp。
 * 刻意不使用 `android.net.Uri`：它会让这段逻辑无法在纯 JVM 单元测试中验证。
 */
object SubscriptionUrl {

    /** 订阅地址最大长度，防止极端输入。 */
    const val MAX_LENGTH = 2048

    /**
     * HTTP(S) URL 语法：`scheme://host[:port][/path]`
     * host 允许字母、数字、点、连字符、下划线、波浪号与百分号编码。
     */
    private val HTTP_URL = Regex(
        "^https?://[A-Za-z0-9._~%-]+(:[0-9]{1,5})?(/.*)?$",
        RegexOption.IGNORE_CASE
    )

    /** 是否为可接受的订阅地址（http / https，且带 host）。 */
    fun isValid(raw: String?): Boolean {
        val trimmed = raw?.trim() ?: return false
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return false
        return HTTP_URL.matches(trimmed)
    }

    /** 从订阅地址提取用于展示的组名（取 host），失败返回空串。 */
    fun displayName(raw: String): String {
        if (!isValid(raw)) return ""
        val withoutScheme = raw.trim().substringAfter("://")
        val host = withoutScheme.substringBefore('/').substringBefore(':')
        return host
    }
}
