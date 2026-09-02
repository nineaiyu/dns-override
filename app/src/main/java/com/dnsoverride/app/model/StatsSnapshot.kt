package com.dnsoverride.app.model

/**
 * 统计数据快照，由 [com.dnsoverride.app.store.StatsStore.snapshot] 生成。
 */
data class StatsSnapshot(
    val totalQueries: Long,
    val blockedCount: Long,
    val forwardedCount: Long,
    val cacheHits: Long,
    val dohUsed: Long,
    val topBlockedDomains: List<DomainStat>,
    val topForwardedDomains: List<DomainStat>
) {
    val blockedRate: Float get() = if (totalQueries == 0L) 0f else blockedCount.toFloat() / totalQueries
}

data class DomainStat(val domain: String, val count: Long)
