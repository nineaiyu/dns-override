package com.dnsoverride.app.hosts

import com.dnsoverride.app.model.RuleAction
import com.dnsoverride.app.model.RuleGroup
import com.google.gson.GsonBuilder

/**
 * 将规则组导出为 hosts 文件格式或 JSON。
 *
 * 动作到 hosts 行的映射：
 * - [RuleAction.OVERRIDE] → `<rule.ip> <domain>`
 * - [RuleAction.BLOCK]    → `0.0.0.0 <domain>`（无论原 ip 是什么，保证再导入时仍识别为拦截）
 * - [RuleAction.DIRECT]   → 无法用 hosts 语义表达，导出为注释行，避免被误当成拦截规则
 *
 * 只导出启用状态的规则；禁用规则直接跳过。
 */
object HostsExporter {

    fun export(group: RuleGroup): String {
        val sb = StringBuilder()
        sb.appendLine("# Exported from DNS Override")
        sb.appendLine("# Group: ${group.name}")
        if (group.description.isNotBlank()) sb.appendLine("# ${group.description}")
        sb.appendLine()

        group.rules.forEach { rule ->
            if (!rule.enabled) return@forEach
            val note = if (rule.note.isNotBlank()) "  # ${rule.note}" else ""
            when (rule.effectiveAction()) {
                RuleAction.BLOCK -> sb.appendLine("0.0.0.0 ${rule.domain}$note")
                RuleAction.OVERRIDE -> sb.appendLine("${rule.ip.ifBlank { "0.0.0.0" }} ${rule.domain}$note")
                RuleAction.DIRECT -> sb.appendLine("# [直连白名单] ${rule.domain}$note")
            }
        }
        return sb.toString()
    }

    fun exportJson(group: RuleGroup): String =
        GsonBuilder().setPrettyPrinting().create().toJson(group)
}
