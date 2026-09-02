package com.dnsoverride.app.store

import android.content.Context
import android.util.Log
import com.dnsoverride.app.hosts.HostsParser
import com.dnsoverride.app.model.DnsRule
import com.dnsoverride.app.model.RuleAction
import com.dnsoverride.app.model.RuleGroup
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 远程规则订阅：规则组绑定一个 URL（hosts 格式或纯域名列表），
 * 手动或定期整体替换组内规则。
 *
 * 解析约定：
 * - hosts 格式 `IP domain`：IP 为 0.0.0.0/127.0.0.1 → 屏蔽规则，否则 → 覆盖到该 IP
 * - 单独一行域名 → 屏蔽规则（常见于 adblock domain list）
 */
object SubscriptionUpdater {

    private const val TAG = "SubscriptionUpdater"

    /** 订阅自动更新的最小间隔。 */
    private const val STALE_MS = 24 * 60 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        data class Updated(val ruleCount: Int) : Result()
        data class Skipped(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** 拉取并替换订阅组规则。[force]=false 时跳过 24h 内已更新的组。 */
    fun refreshGroup(context: Context, group: RuleGroup, force: Boolean): Result {
        if (group.sourceUrl.isBlank()) return Result.Skipped("非订阅组")
        if (!force && System.currentTimeMillis() - group.lastSyncAt < STALE_MS) {
            return Result.Skipped("订阅仍新鲜")
        }

        val body = runCatching {
            client.newCall(Request.Builder().url(group.sourceUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string() ?: error("空响应")
            }
        }.getOrElse {
            Log.w(TAG, "fetch ${group.sourceUrl} failed: ${it.message}")
            return Result.Failed("拉取失败: ${it.message}")
        }

        val rules = HostsParser.parse(body).map { parsed ->
            DnsRule(
                domain = parsed.domain,
                ip = parsed.ip,
                action = if (HostsParser.isAdBlockStyle(parsed.ip)) RuleAction.BLOCK else RuleAction.OVERRIDE,
                note = parsed.note.ifBlank { "订阅" }
            )
        }
        if (rules.isEmpty()) return Result.Failed("未解析到任何规则")

        RuleStore.get(context).upsertGroup(
            group.copy(rules = rules, lastSyncAt = System.currentTimeMillis())
        )
        Log.i(TAG, "subscription '${group.name}' updated with ${rules.size} rules")
        return Result.Updated(rules.size)
    }

    /** 刷新所有订阅组（force=false 时只刷新过期的）。返回 (组名 → 结果)。 */
    fun refreshOutdated(context: Context, force: Boolean = false): Map<String, Result> {
        return RuleStore.get(context).listGroups()
            .filter { it.isSubscription }
            .associate { group ->
                val name = group.name
                name to runCatching { refreshGroup(context, group, force) }
                    .getOrElse { Result.Failed(it.message ?: "未知错误") }
            }
    }
}
