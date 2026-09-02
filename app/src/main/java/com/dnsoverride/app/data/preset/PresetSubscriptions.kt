package com.dnsoverride.app.data.preset

/**
 * 内置「常用规则订阅库」。
 *
 * 这些均为社区维护的**公开 hosts 列表**，地址长期稳定、以 `IP 域名` 形式提供，
 * 可被 [com.dnsoverride.app.hosts.HostsParser] 直接解析。
 *
 * 注意：应用**不**对这些第三方列表的内容背书，是否启用、启用哪些请用户自行甄别与负责。
 */
data class PresetSubscription(
    val id: String,
    val name: String,
    val url: String,
    val description: String,
    val category: String
)

object PresetSubscriptions {
    val items: List<PresetSubscription> = listOf(
        PresetSubscription(
            id = "preset_stevenblack",
            name = "广告拦截（综合）",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            description = "合并广告、追踪器与恶意域名的综合 hosts 列表，社区使用最广泛。",
            category = "广告拦截"
        ),
        PresetSubscription(
            id = "preset_adaway",
            name = "AdAway 默认广告列表",
            url = "https://raw.githubusercontent.com/AdAway/AdAway/master/adaway_hosts.txt",
            description = "AdAway 内置的广告 hosts 清单，覆盖面广、更新频繁。",
            category = "广告拦截"
        ),
        PresetSubscription(
            id = "preset_someonewhocares",
            name = "隐私保护（someonewhocares）",
            url = "https://someonewhocares.org/hosts/zero/hosts",
            description = "拦截追踪与隐私泄露域名的 hosts 列表。",
            category = "隐私保护"
        ),
        PresetSubscription(
            id = "preset_coinblocker",
            name = "挖矿/恶意软件拦截",
            url = "https://raw.githubusercontent.com/ZeroDot1/CoinBlockerLists/master/hosts",
            description = "屏蔽加密货币挖矿与已知恶意软件域名的 hosts 列表。",
            category = "恶意软件"
        )
    )

    /** 判断某订阅组（按 sourceUrl）是否已添加为内置订阅。 */
    fun findById(id: String): PresetSubscription? = items.firstOrNull { it.id == id }
}
