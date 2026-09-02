package com.dnsoverride.app.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HostsParser 边界情况测试：真实世界的 hosts / 拦截列表往往格式不规整。
 */
class HostsParserEdgeCaseTest {

    @Test
    fun handles_crlf_line_endings() {
        val r = HostsParser.parse("0.0.0.0 a.com\r\n0.0.0.0 b.com\r\n")
        assertEquals(2, r.size)
        assertEquals("a.com", r[0].domain)
        assertEquals("b.com", r[1].domain)
    }

    @Test
    fun lowercases_domains_and_trims() {
        val r = HostsParser.parse("  0.0.0.0    EXAMPLE.COM   ")
        assertEquals(1, r.size)
        assertEquals("example.com", r[0].domain)
        assertEquals("0.0.0.0", r[0].ip)
    }

    @Test
    fun parses_bare_domain_list_as_block() {
        // adblock 风格：一行一个域名，没有 IP
        val r = HostsParser.parse("ads.example.com\ntracker.example.com\n")
        assertEquals(2, r.size)
        assertTrue(r.all { it.ip == "0.0.0.0" })
        assertTrue(r.all { HostsParser.isAdBlockStyle(it.ip) })
    }

    @Test
    fun skips_lines_without_recognizable_ip() {
        // "1.2.3" 不是合法 IPv4（只有 3 段），整行跳过
        val r = HostsParser.parse("1.2.3 bad.com\n192.168.1.1 good.com\n")
        assertEquals(1, r.size)
        assertEquals("good.com", r[0].domain)
    }

    @Test
    fun supports_ipv6_addresses() {
        val r = HostsParser.parse("::1 localhost\nfe80::1 router.local\n")
        assertEquals(2, r.size)
        assertEquals("::1", r[0].ip)
        assertEquals("fe80::1", r[1].ip)
    }

    @Test
    fun handles_tabs_and_multiple_spaces() {
        val r = HostsParser.parse("10.0.0.1\t\ta.com\t\tb.com")
        assertEquals(2, r.size)
        assertEquals("a.com", r[0].domain)
        assertEquals("b.com", r[1].domain)
    }

    @Test
    fun ignores_comment_only_and_blank_lines() {
        val text = """
            # 这是注释
            # 这也是

            0.0.0.0 real.com
        """.trimIndent()
        val r = HostsParser.parse(text)
        assertEquals(1, r.size)
        assertEquals("real.com", r[0].domain)
    }

    @Test
    fun ignores_inline_comments() {
        val r = HostsParser.parse("0.0.0.0 ad.com # 广告域名")
        assertEquals(1, r.size)
        assertEquals("ad.com", r[0].domain)
    }

    @Test
    fun returns_empty_for_empty_input() {
        assertTrue(HostsParser.parse("").isEmpty())
        assertTrue(HostsParser.parse("# only comment\n\n").isEmpty())
    }

    @Test
    fun rejects_ips_with_out_of_range_octets() {
        val r = HostsParser.parse("999.1.1.1 bad.com")
        assertTrue(r.isEmpty())
    }

    @Test
    fun localhost_entries_are_recognized_as_block_style() {
        val r = HostsParser.parse("127.0.0.1 localhost\n127.0.0.1 ad.com")
        assertTrue(r.all { HostsParser.isAdBlockStyle(it.ip) })
    }
}
