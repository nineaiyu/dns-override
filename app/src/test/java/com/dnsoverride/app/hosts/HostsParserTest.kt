package com.dnsoverride.app.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HostsParser 解析逻辑测试。
 */
class HostsParserTest {

    @Test
    fun parses_simple_line() {
        val r = HostsParser.parse("192.168.1.1 example.com")
        assertEquals(1, r.size)
        assertEquals("example.com", r[0].domain)
        assertEquals("192.168.1.1", r[0].ip)
    }

    @Test
    fun parses_multiple_domains_per_line() {
        val r = HostsParser.parse("0.0.0.0 ad1.com ad2.com ad3.com")
        assertEquals(3, r.size)
        assertTrue(r.all { it.ip == "0.0.0.0" })
    }

    @Test
    fun skips_comments_and_blanks() {
        val text = """
            # comment

            127.0.0.1 blocked.com  # inline comment
        """.trimIndent()
        val r = HostsParser.parse(text)
        assertEquals(1, r.size)
        assertEquals("blocked.com", r[0].domain)
    }

    @Test
    fun detects_adblock_style() {
        assertTrue(HostsParser.isAdBlockStyle("0.0.0.0"))
        assertTrue(HostsParser.isAdBlockStyle("127.0.0.1"))
        assertFalse(HostsParser.isAdBlockStyle("192.168.1.1"))
    }

    @Test
    fun skips_invalid_ip() {
        val r = HostsParser.parse("not.an.ip example.com")
        assertTrue(r.isEmpty())
    }

    @Test
    fun empty_input_returns_empty() {
        assertTrue(HostsParser.parse("").isEmpty())
        assertTrue(HostsParser.parse("# only comment").isEmpty())
    }

    @Test
    fun domain_only_line_becomes_block_rule() {
        val r = HostsParser.parse("ads.example.com")
        assertEquals(1, r.size)
        assertEquals("ads.example.com", r[0].domain)
        assertEquals("0.0.0.0", r[0].ip)
        assertTrue(HostsParser.isAdBlockStyle(r[0].ip))
    }

    @Test
    fun bare_ip_line_is_not_treated_as_domain() {
        assertTrue(HostsParser.parse("192.168.1.1").isEmpty())
        assertTrue(HostsParser.parse("0.0.0.0").isEmpty())
    }

    @Test
    fun mixed_hosts_and_domain_list() {
        val text = """
            # mixed subscription content
            10.0.0.1 api.example.com
            tracker.example.net
            0.0.0.0 ads.example.com
        """.trimIndent()
        val r = HostsParser.parse(text)
        assertEquals(3, r.size)
        assertEquals("10.0.0.1", r[0].ip)
        assertEquals("0.0.0.0", r[1].ip)
        assertEquals("0.0.0.0", r[2].ip)
    }
}
