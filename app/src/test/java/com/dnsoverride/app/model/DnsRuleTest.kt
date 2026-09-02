package com.dnsoverride.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DnsRule 匹配逻辑测试。
 */
class DnsRuleTest {

    @Test
    fun exact_match_case_insensitive() {
        assertTrue(DnsRule(domain = "Example.com", ip = "1.2.3.4").matches("example.com"))
        assertTrue(DnsRule(domain = "example.com", ip = "1.2.3.4").matches("EXAMPLE.COM"))
    }

    @Test
    fun wildcard_matches_subdomain_and_base() {
        val r = DnsRule(domain = "*.example.com", ip = "1.2.3.4")
        assertTrue(r.matches("example.com"))
        assertTrue(r.matches("a.example.com"))
        assertTrue(r.matches("a.b.example.com"))
        assertFalse(r.matches("notexample.com"))
    }

    @Test
    fun trailing_dot_normalized() {
        assertTrue(DnsRule(domain = "example.com", ip = "1.2.3.4").matches("example.com."))
        assertTrue(DnsRule(domain = "example.com.", ip = "1.2.3.4").matches("example.com"))
    }

    @Test
    fun whitelist_rule_still_matches_domain() {
        // whitelist 影响行为但不影响 matches
        val r = DnsRule(domain = "example.com", ip = "0.0.0.0", whitelist = true)
        assertTrue(r.matches("example.com"))
    }

    @Test
    fun empty_domain_does_not_match() {
        val r = DnsRule(domain = "example.com", ip = "1.2.3.4")
        assertFalse(r.matches(""))
    }

    @Test
    fun effective_action_defaults_to_override() {
        assertEquals(RuleAction.OVERRIDE, DnsRule(domain = "a.com", ip = "1.2.3.4").effectiveAction())
    }

    @Test
    fun effective_action_from_legacy_whitelist() {
        // 旧版 JSON 没有 action 字段（反序列化后为 null），whitelist=true 应归一为 DIRECT
        val legacy = DnsRule(domain = "a.com", ip = "0.0.0.0", whitelist = true, action = null)
        assertEquals(RuleAction.DIRECT, legacy.effectiveAction())
    }

    @Test
    fun effective_action_explicit_value_wins() {
        assertEquals(
            RuleAction.BLOCK,
            DnsRule(domain = "a.com", ip = "0.0.0.0", whitelist = true, action = RuleAction.BLOCK).effectiveAction()
        )
        assertEquals(
            RuleAction.DIRECT,
            DnsRule(domain = "a.com", ip = "1.2.3.4", whitelist = false, action = RuleAction.DIRECT).effectiveAction()
        )
    }
}
