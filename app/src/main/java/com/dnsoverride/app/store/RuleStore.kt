package com.dnsoverride.app.store

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.dnsoverride.app.model.DnsRule
import com.dnsoverride.app.model.RuleGroup
import com.dnsoverride.app.util.ConflictDetector
import java.util.UUID

/**
 * 规则持久化：基于 SharedPreferences + Gson。
 *
 * 存储两份数据：
 * - 规则组列表（含预设）
 * - 当前激活规则组 id
 *
 * 通过 [RuleStore] 单例访问，避免多实例缓存不一致。
 */
class RuleStore private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    init {
        migrateFromLegacyIfNeeded()
    }

    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun listGroups(): List<RuleGroup> {
        val raw = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        val groups = runCatching {
            val type = object : TypeToken<List<RuleGroup>>() {}.type
            gson.fromJson<List<RuleGroup>>(raw, type) ?: emptyList()
        }.getOrElse { emptyList() }
        // 兼容旧版 JSON：缺失的字段 Gson 会绕过 Kotlin 默认值直接反射赋 null，
        // 而 data class 字段声明为非空，导致后续访问（如 isSubscription 的 sourceUrl.isNotBlank()）NPE 崩溃。
        // 这里把所有可能为 null 的字段归一到默认值。编译器认为字段非空无法感知 Gson 的 null，
        // 故用 @Suppress 关闭误报，但运行时归一是真实必要的。
        return groups.map { g ->
            g.copy(
                id = g.id.ifBlank { java.util.UUID.randomUUID().toString() },
                name = g.name ?: "",
                description = g.description ?: "",
                rules = g.rules.map {
                    it.copy(
                        domain = it.domain ?: "",
                        ip = it.ip ?: "",
                        note = it.note ?: "",
                        action = it.action ?: it.effectiveAction()
                    )
                },
                sourceUrl = g.sourceUrl ?: "",
                lastSyncAt = g.lastSyncAt ?: 0L,
                createdAt = g.createdAt ?: System.currentTimeMillis()
            )
        }
    }

    fun saveGroups(groups: List<RuleGroup>) {
        prefs.edit().putString(KEY_GROUPS, gson.toJson(groups)).apply()
    }

    fun getGroup(id: String): RuleGroup? = listGroups().firstOrNull { it.id == id }

    fun upsertGroup(group: RuleGroup) {
        val groups = listGroups().toMutableList()
        val idx = groups.indexOfFirst { it.id == group.id }
        if (idx >= 0) groups[idx] = group else groups.add(group)
        saveGroups(groups)
    }

    fun deleteGroup(id: String) {
        saveGroups(listGroups().filterNot { it.id == id })
    }

    /**
     * 返回所有规则组中所有启用的规则（按组顺序 + 组内顺序）。
     *
     * 关键设计：不再有"激活组"概念，所有组的启用规则都同时参与匹配。
     * 组内规则位置即优先级（越靠前优先级越高），组间按组列表顺序。
     */
    fun getAllEnabledRules(): List<DnsRule> {
        return listGroups().flatMap { it.rules }.filter { it.enabled }
    }

    /**
     * 分析所有启用组中的规则冲突（重复域名 / 覆盖与屏蔽互斥 / 订阅与本地冲突 / 通配笼罩）。
     * 计算结果不缓存，调用方按需（如列表刷新、保存规则后）调用。
     */
    fun detectConflicts(): List<ConflictDetector.Conflict> {
        return ConflictDetector.detect(listGroups().filter { it.enabled })
    }

    /** 只返回涉及指定规则 id 的冲突（用于保存单条规则后提示）。 */
    fun conflictsForRule(ruleId: String): List<ConflictDetector.Conflict> {
        return detectConflicts().filter { ruleId in it.ruleIds }
    }

    /** 批量设置所有规则组中所有规则的启用状态。 */
    fun setAllEnabled(enabled: Boolean) {
        val groups = listGroups().map { g ->
            g.copy(rules = g.rules.map { it.copy(enabled = enabled) })
        }
        saveGroups(groups)
    }

    /** 批量设置某组内所有规则的启用状态（用于「全部启用/禁用」）。 */
    fun setGroupAllEnabled(groupId: String, enabled: Boolean) {
        val groups = listGroups().map { g ->
            if (g.id == groupId) g.copy(rules = g.rules.map { it.copy(enabled = enabled) })
            else g
        }
        saveGroups(groups)
    }

    /**
     * 调整某组内规则顺序（优先级：越靠前优先级越高）。
     * 订阅组为只读，调用方应禁止对其排序。返回是否成功（组不存在则 false）。
     */
    fun reorderRules(groupId: String, from: Int, to: Int): Boolean {
        val groups = listGroups().toMutableList()
        val gi = groups.indexOfFirst { it.id == groupId }
        if (gi < 0) return false
        val rules = groups[gi].rules.toMutableList()
        if (from !in rules.indices || to !in rules.indices || from == to) return false
        val moved = rules.removeAt(from)
        rules.add(to, moved)
        groups[gi] = groups[gi].copy(rules = rules)
        saveGroups(groups)
        return true
    }

    /** 若用户首次启动没有任何规则组，从 assets/default_rules.json 写入预设组。 */
    fun ensureDefaultSeed() {
        if (listGroups().isNotEmpty()) return
        val (groups, _) = loadDefaultFromAssets() ?: return
        saveGroups(groups)
    }

    /**
     * 迁移旧版 PrintHub 偏好（printhub_dns_override）到新 key（dns_override_rules）。
     * 仅在用户从旧版升级时触发一次。
     */
    private fun migrateFromLegacyIfNeeded() {
        if (prefs.contains(KEY_GROUPS)) return
        val legacy = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyGroups = legacy.getString(KEY_GROUPS, null) ?: return
        prefs.edit().putString(KEY_GROUPS, legacyGroups).apply()
    }

    private fun loadDefaultFromAssets(): Pair<List<RuleGroup>, String?>? {
        val text = runCatching {
            appContext.assets.open("default_rules.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null

        // 优先解析新版多组格式
        val multi = runCatching { gson.fromJson(text, DefaultRulesMulti::class.java) }.getOrNull()
        if (multi?.groups?.isNotEmpty() == true) {
            val groups = multi.groups.map { g ->
                RuleGroup(
                    id = UUID.randomUUID().toString(),
                    name = g.name,
                    enabled = false,
                    // 必须使用命名参数：DnsRule 的 id 有默认值，位置参数会从 id 开始填充，
                    // 导致 domain 误填到 id、ip 误填到 domain、note 误填到 ip。
                    rules = g.rules.map { DnsRule(domain = it.domain, ip = it.ip, note = it.note) }
                )
            }
            return groups to multi.activeGroup
        }

        // 兼容旧版单组格式
        val single = runCatching { gson.fromJson(text, DefaultRulesFile::class.java) }.getOrNull()
            ?: return null
        val group = RuleGroup(
            id = UUID.randomUUID().toString(),
            name = single.name,
            enabled = true,
            rules = single.rules.map { DnsRule(domain = it.domain, ip = it.ip, note = it.note) }
        )
        return listOf(group) to null
    }

    // 新版多组格式
    private data class DefaultRulesMulti(
        val groups: List<DefaultGroup>?,
        val activeGroup: String?
    )

    private data class DefaultGroup(
        val name: String,
        val description: String,
        val rules: List<DefaultRule>
    )

    // 旧版单组格式（向后兼容）
    private data class DefaultRulesFile(
        val name: String,
        val description: String,
        val rules: List<DefaultRule>
    )

    private data class DefaultRule(
        val domain: String,
        val ip: String,
        val note: String
    )

    companion object {
        private const val PREFS_NAME = "dns_override_rules"
        private const val LEGACY_PREFS_NAME = "printhub_dns_override"
        private const val KEY_GROUPS = "rule_groups"

        @Volatile private var instance: RuleStore? = null

        fun get(context: Context): RuleStore =
            instance ?: synchronized(this) {
                instance ?: RuleStore(context).also { instance = it }
            }
    }
}
