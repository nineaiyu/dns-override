package com.dnsoverride.app.util

import com.dnsoverride.app.model.DnsRule
import com.dnsoverride.app.model.RuleAction
import com.dnsoverride.app.model.RuleGroup

/**
 * 规则冲突检测。
 *
 * 命中即意味着：实际匹配结果可能与用户预期不一致（多条规则作用于同一域名时，
 * 只有优先级最高的一条生效，其余被「遮蔽」）。本类只做静态分析，不修改任何数据。
 *
 * 支持检测的冲突类型：
 * 1. [Type.DUPLICATE]：同一组内存在完全相同的域名（精确或同一通配），规则冗余；
 * 2. [Type.OVERRIDE_VS_BLOCK]：同一域名同时存在「覆盖」与「屏蔽」两类动作，
 *    二者语义互斥，只能生效其一，属于高优先级（错误级）冲突；
 * 3. [Type.SUBSCRIPTION_VS_LOCAL]：订阅组与本地组对同一域名给出了不同处理；
 * 4. [Type.WILDCARD_OVERLAP]：精确域名 `a.b.com` 被通配 `*.b.com` 笼罩，
 *    若通配规则更靠前，精确规则永远不会生效。
 */
object ConflictDetector {

    enum class Severity { INFO, WARN, ERROR }

    enum class Type {
        DUPLICATE,
        OVERRIDE_VS_BLOCK,
        SUBSCRIPTION_VS_LOCAL,
        WILDCARD_OVERLAP
    }

    data class Conflict(
        val domain: String,
        val severity: Severity,
        val type: Type,
        val message: String,
        val ruleIds: List<String>
    )

    /** 对传入的（已启用）组集合做冲突分析。建议仅传入 [RuleGroup.enabled] == true 的组及其启用规则。 */
    fun detect(groups: List<RuleGroup>): List<Conflict> {
        val byExact = mutableMapOf<String, MutableList<RuleRef>>()
        val wildcards = mutableListOf<RuleRef>()

        for (group in groups) {
            for (rule in group.rules.filter { it.enabled }) {
                val ref = RuleRef(rule, group)
                val d = normalize(rule.domain)
                if (rule.domain.startsWith("*.")) {
                    wildcards.add(ref)
                } else {
                    byExact.getOrPut(d) { mutableListOf() }.add(ref)
                }
            }
        }

        val conflicts = mutableListOf<Conflict>()

        // 1 & 2 & 3：基于精确域名的同一域名多规则
        for ((domain, refs) in byExact) {
            if (refs.size >= 2) {
                conflicts += duplicateAndActionConflicts(domain, refs)
                conflicts += subscriptionVsLocal(domain, refs)
            }
        }

        // 4：通配笼罩精确域名
        for (wc in wildcards) {
            val base = normalize(wc.rule.domain.removePrefix("*."))
            for ((domain, refs) in byExact) {
                if (domain == base || domain.endsWith(".$base")) {
                    conflicts += Conflict(
                        domain = domain,
                        severity = Severity.INFO,
                        type = Type.WILDCARD_OVERLAP,
                        message = "精确规则 $domain 被通配规则 ${wc.rule.domain} 笼罩，可能永不生效（取决于优先级）。",
                        ruleIds = refs.map { it.rule.id } + wc.rule.id
                    )
                }
            }
            // 订阅通配 vs 本地精确 也在此提示
            for ((domain, refs) in byExact) {
                if ((domain == base || domain.endsWith(".$base")) && wc.group.isSubscription) {
                    val local = refs.find { !it.group.isSubscription }
                    if (local != null) {
                        conflicts += Conflict(
                            domain = domain,
                            severity = Severity.INFO,
                            type = Type.SUBSCRIPTION_VS_LOCAL,
                            message = "订阅通配 ${wc.rule.domain} 与本地规则 $domain 作用于同一域名，按优先级生效。",
                            ruleIds = refs.map { it.rule.id } + wc.rule.id
                        )
                    }
                }
            }
        }

        return conflicts.distinctBy { it.type to it.domain }
    }

    private fun duplicateAndActionConflicts(domain: String, refs: List<RuleRef>): List<Conflict> {
        val out = mutableListOf<Conflict>()
        val actions = refs.map { it.rule.effectiveAction() }
        val hasOverride = RuleAction.OVERRIDE in actions
        val hasBlock = RuleAction.BLOCK in actions
        val hasDirect = RuleAction.DIRECT in actions
        val ids = refs.map { it.rule.id }

        if (hasOverride && hasBlock) {
            out += Conflict(
                domain = domain,
                severity = Severity.ERROR,
                type = Type.OVERRIDE_VS_BLOCK,
                message = "域名 $domain 同时存在「覆盖」与「屏蔽」规则，只会按优先级生效其一，另一规则被遮蔽。",
                ruleIds = ids
            )
        } else if (actions.distinct().size >= 2 || refs.size >= 2) {
            // 重复域名（即便动作相同也属冗余）
            out += Conflict(
                domain = domain,
                severity = Severity.WARN,
                type = Type.DUPLICATE,
                message = "域名 $domain 存在 ${refs.size} 条重复规则（动作：${actions.distinct().joinToString("/") { it.label() }}），仅优先级最高者生效。",
                ruleIds = ids
            )
        }
        // 仅同动作重复也提示（上面的分支已覆盖 refs.size>=2 但动作单一的情况）
        if (actions.distinct().size == 1 && refs.size >= 2 && !hasOverride && !hasBlock && !hasDirect) {
            // 已在上面 WARN 分支
        }
        return out
    }

    private fun subscriptionVsLocal(domain: String, refs: List<RuleRef>): List<Conflict> {
        val sub = refs.find { it.group.isSubscription }
        val local = refs.find { !it.group.isSubscription }
        if (sub != null && local != null && sub.rule.effectiveAction() != local.rule.effectiveAction()) {
            return listOf(
                Conflict(
                    domain = domain,
                    severity = Severity.WARN,
                    type = Type.SUBSCRIPTION_VS_LOCAL,
                    message = "域名 $domain 在订阅组「${sub.group.name}」与本地组「${local.group.name}」中处理不一致，按优先级生效。",
                    ruleIds = refs.map { it.rule.id }
                )
            )
        }
        return emptyList()
    }

    private fun normalize(domain: String): String =
        domain.lowercase().trim().trimEnd('.')

    private data class RuleRef(val rule: DnsRule, val group: RuleGroup)

    private fun RuleAction.label(): String = when (this) {
        RuleAction.OVERRIDE -> "覆盖"
        RuleAction.BLOCK -> "屏蔽"
        RuleAction.DIRECT -> "直连"
    }
}
