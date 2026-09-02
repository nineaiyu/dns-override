package com.dnsoverride.app.model

import java.util.UUID

/**
 * 一组 DNS 规则。可同时存在多组，但只有 [enabled] 为 true 的组才会参与匹配。
 *
 * [sourceUrl] 非空表示这是一个订阅组：规则内容来自远程 hosts / 域名列表，
 * 由 SubscriptionUpdater 定期（或手动）整体替换，[lastSyncAt] 记录上次成功拉取时间。
 */
data class RuleGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val rules: List<DnsRule> = emptyList(),
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val sourceUrl: String = "",
    val lastSyncAt: Long = 0
) {
    val isSubscription: Boolean get() = sourceUrl.isNotBlank()
}
