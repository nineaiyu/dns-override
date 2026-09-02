package com.dnsoverride.app.util

import com.dnsoverride.app.model.DnsRule
import com.dnsoverride.app.model.RuleAction
import com.dnsoverride.app.model.RuleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictDetectorTest {

    private fun rule(domain: String, action: RuleAction, enabled: Boolean = true) =
        DnsRule(domain = domain, ip = "", action = action, enabled = enabled)

    private fun group(vararg rules: DnsRule, subscription: Boolean = false) = RuleGroup(
        id = "g",
        name = "g",
        rules = rules.toList(),
        sourceUrl = if (subscription) "https://example.com/hosts.txt" else ""
    )

    @Test
    fun no_conflict_for_single_rule() {
        val conflicts = ConflictDetector.detect(listOf(group(rule("a.com", RuleAction.OVERRIDE))))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun duplicate_domain_in_same_group_is_flagged() {
        val conflicts = ConflictDetector.detect(
            listOf(group(rule("a.com", RuleAction.OVERRIDE), rule("a.com", RuleAction.OVERRIDE)))
        )
        val dup = conflicts.firstOrNull { it.type == ConflictDetector.Type.DUPLICATE }
        assertEquals(ConflictDetector.Severity.WARN, dup?.severity)
        assertEquals(2, dup?.ruleIds?.size)
    }

    @Test
    fun override_vs_block_same_domain_is_error() {
        val conflicts = ConflictDetector.detect(
            listOf(group(rule("a.com", RuleAction.OVERRIDE), rule("a.com", RuleAction.BLOCK)))
        )
        val c = conflicts.firstOrNull { it.type == ConflictDetector.Type.OVERRIDE_VS_BLOCK }
        assertEquals(ConflictDetector.Severity.ERROR, c?.severity)
        assertEquals(2, c?.ruleIds?.size)
    }

    @Test
    fun subscription_vs_local_same_domain_is_flagged() {
        val local = group(rule("a.com", RuleAction.OVERRIDE))
        val sub = group(rule("a.com", RuleAction.BLOCK), subscription = true)
        val conflicts = ConflictDetector.detect(listOf(local, sub))
        val c = conflicts.firstOrNull { it.type == ConflictDetector.Type.SUBSCRIPTION_VS_LOCAL }
        assertEquals(ConflictDetector.Severity.WARN, c?.severity)
        assertEquals(2, c?.ruleIds?.size)
    }

    @Test
    fun wildcard_overlaps_exact_domain() {
        val conflicts = ConflictDetector.detect(
            listOf(group(rule("*.b.com", RuleAction.OVERRIDE), rule("a.b.com", RuleAction.OVERRIDE)))
        )
        val c = conflicts.firstOrNull { it.type == ConflictDetector.Type.WILDCARD_OVERLAP }
        assertEquals(ConflictDetector.Severity.INFO, c?.severity)
        assertEquals("a.b.com", c?.domain)
    }

    @Test
    fun disabled_rules_are_ignored() {
        val conflicts = ConflictDetector.detect(
            listOf(
                group(
                    rule("a.com", RuleAction.OVERRIDE, enabled = false),
                    rule("a.com", RuleAction.BLOCK, enabled = false)
                )
            )
        )
        assertTrue(conflicts.none { it.type == ConflictDetector.Type.OVERRIDE_VS_BLOCK })
    }
}
