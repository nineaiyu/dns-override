package com.dnsoverride.app.hosts

import com.dnsoverride.app.model.DnsRule
import com.dnsoverride.app.model.RuleAction
import com.dnsoverride.app.model.RuleGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HostsExporter 导出逻辑测试。
 */
class HostsExporterTest {

    @Test
    fun exports_enabled_rules_only() {
        val g = RuleGroup(
            name = "test",
            rules = listOf(
                DnsRule(domain = "a.com", ip = "1.1.1.1", enabled = true),
                DnsRule(domain = "b.com", ip = "2.2.2.2", enabled = false)
            )
        )
        val text = HostsExporter.export(g)
        assertTrue(text.contains("1.1.1.1 a.com"))
        assertFalse(text.contains("2.2.2.2"))
    }

    @Test
    fun block_action_uses_zero_ip() {
        val g = RuleGroup(
            name = "test",
            rules = listOf(
                // 屏蔽规则即使填了别的 IP，导出也必须是 0.0.0.0，否则再导入会被识别成覆盖
                DnsRule(domain = "ad.com", ip = "1.2.3.4", action = RuleAction.BLOCK)
            )
        )
        val text = HostsExporter.export(g)
        assertTrue(text.contains("0.0.0.0 ad.com"))
        assertFalse(text.contains("1.2.3.4 ad.com"))
    }

    @Test
    fun override_action_keeps_original_ip() {
        val g = RuleGroup(
            name = "test",
            rules = listOf(DnsRule(domain = "a.com", ip = "10.0.0.5", action = RuleAction.OVERRIDE))
        )
        assertTrue(HostsExporter.export(g).contains("10.0.0.5 a.com"))
    }

    @Test
    fun direct_action_exported_as_comment() {
        val g = RuleGroup(
            name = "test",
            rules = listOf(DnsRule(domain = "pass.com", ip = "1.2.3.4", action = RuleAction.DIRECT))
        )
        val text = HostsExporter.export(g)
        assertTrue(text.contains("# [直连白名单] pass.com"))
        // 不能产生任何可解析的 hosts 行，避免被再导入成拦截规则
        assertFalse(text.lines().any { it.startsWith("1.2.3.4 pass.com") })
    }

    @Test
    fun legacy_whitelist_rule_is_treated_as_direct() {
        // 旧版数据：无 action 字段，whitelist=true → effectiveAction() 归一为 DIRECT
        val g = RuleGroup(
            name = "test",
            rules = listOf(DnsRule(domain = "ad.com", ip = "0.0.0.0", whitelist = true))
        )
        assertTrue(HostsExporter.export(g).contains("# [直连白名单] ad.com"))
    }

    @Test
    fun includes_group_name_in_header() {
        val g = RuleGroup(name = "MyGroup", rules = emptyList())
        val text = HostsExporter.export(g)
        assertTrue(text.contains("Group: MyGroup"))
    }

    @Test
    fun includes_note_as_inline_comment() {
        val g = RuleGroup(
            name = "test",
            rules = listOf(DnsRule(domain = "a.com", ip = "1.1.1.1", note = "my note"))
        )
        val text = HostsExporter.export(g)
        assertTrue(text.contains("# my note"))
    }
}
