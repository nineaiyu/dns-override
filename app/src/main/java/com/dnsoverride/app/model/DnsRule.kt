package com.dnsoverride.app.model

import java.util.UUID

/**
 * 规则动作。
 * - [OVERRIDE]：把域名解析结果覆盖为指定 IP
 * - [BLOCK]：屏蔽该域名（返回 0.0.0.0 或 NXDOMAIN，由设置决定）
 * - [DIRECT]：直连白名单，不拦截，强制走上游
 */
enum class RuleAction { OVERRIDE, BLOCK, DIRECT }

/**
 * 一条 DNS 拦截规则。
 *
 * [domain] 支持精确匹配或通配符（以 `*.` 开头）；[enabled] 控制单条规则是否启用。
 * 动作由 [action] 决定，旧数据只有 [whitelist] 布尔字段，读取时经 [effectiveAction] 归一。
 */
data class DnsRule(
    val id: String = UUID.randomUUID().toString(),
    val domain: String,
    val ip: String,
    val note: String = "",
    val ttl: Int = 60,
    val enabled: Boolean = true,
    /** 旧版白名单字段（已被 [action] 取代，保留以兼容已存储的 JSON 数据）。 */
    val whitelist: Boolean = false,
    /** Gson 反序列化旧 JSON 时不会走默认值，可能为 null，使用时必须经 [effectiveAction]。 */
    val action: RuleAction? = null
) {
    /**
     * 判断 [queryDomain] 是否命中本规则。
     *
     * - 精确域名：忽略大小写完全相等。
     * - 通配符 `*.example.com`：匹配 `example.com` 自身及其所有子域名。
     */
    fun matches(queryDomain: String): Boolean {
        val q = queryDomain.lowercase().trimEnd('.')
        val d = domain.lowercase().trimEnd('.')
        return when {
            d.startsWith("*.") -> {
                val base = d.substring(2)
                q == base || q.endsWith(".$base")
            }
            else -> q == d
        }
    }

    /** 归一后的动作：新数据看 [action]，旧数据回退到 [whitelist]。 */
    fun effectiveAction(): RuleAction =
        action ?: if (whitelist) RuleAction.DIRECT else RuleAction.OVERRIDE
}
