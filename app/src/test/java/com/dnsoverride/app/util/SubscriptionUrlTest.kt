package com.dnsoverride.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅 URL 校验测试。
 *
 * 回归点：早期版本用 `url.startsWith("http")` 判断，
 * 会把 "httpfoo" 这类字符串判为合法，交给 OkHttp 后崩溃。
 */
class SubscriptionUrlTest {

    @Test
    fun accepts_https_url() {
        assertTrue(SubscriptionUrl.isValid("https://example.com/hosts.txt"))
    }

    @Test
    fun accepts_http_url() {
        assertTrue(SubscriptionUrl.isValid("http://192.168.1.10:8080/hosts"))
    }

    @Test
    fun accepts_host_without_path() {
        assertTrue(SubscriptionUrl.isValid("https://raw.githubusercontent.com"))
    }

    @Test
    fun rejects_non_url_strings() {
        // 这是老实现的回归点：startsWith("http") 会误判为合法
        assertFalse(SubscriptionUrl.isValid("httpfoo"))
        assertFalse(SubscriptionUrl.isValid("httpx://example.com"))
        assertFalse(SubscriptionUrl.isValid("example.com/hosts.txt"))
        assertFalse(SubscriptionUrl.isValid("just some text"))
    }

    @Test
    fun rejects_dangerous_schemes() {
        assertFalse(SubscriptionUrl.isValid("file:///etc/hosts"))
        assertFalse(SubscriptionUrl.isValid("ftp://example.com/hosts"))
        assertFalse(SubscriptionUrl.isValid("content://media/hosts"))
    }

    @Test
    fun rejects_blank_and_oversized() {
        assertFalse(SubscriptionUrl.isValid(null))
        assertFalse(SubscriptionUrl.isValid(""))
        assertFalse(SubscriptionUrl.isValid("   "))
        assertFalse(SubscriptionUrl.isValid("https://example.com/" + "a".repeat(SubscriptionUrl.MAX_LENGTH)))
    }

    @Test
    fun trims_surrounding_whitespace() {
        assertTrue(SubscriptionUrl.isValid("  https://example.com/hosts.txt  "))
    }

    @Test
    fun extracts_host_as_display_name() {
        assertEquals("example.com", SubscriptionUrl.displayName("https://example.com/a/b/hosts.txt"))
        assertEquals("raw.githubusercontent.com", SubscriptionUrl.displayName("https://raw.githubusercontent.com/x"))
        assertEquals("", SubscriptionUrl.displayName("not a url"))
    }
}
