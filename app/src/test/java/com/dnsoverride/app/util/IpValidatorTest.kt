package com.dnsoverride.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IpValidator 域名与 IP 校验测试。
 */
class IpValidatorTest {

    @Test
    fun valid_ipv4() {
        assertTrue(IpValidator.isValidIp("192.168.1.1"))
        assertTrue(IpValidator.isValidIp("8.8.8.8"))
        assertTrue(IpValidator.isValidIp("0.0.0.0"))
        assertTrue(IpValidator.isValidIp("255.255.255.255"))
    }

    @Test
    fun invalid_ipv4() {
        assertFalse(IpValidator.isValidIp("999.1.1.1"))
        assertFalse(IpValidator.isValidIp("1.2.3"))
        assertFalse(IpValidator.isValidIp("abc"))
        assertFalse(IpValidator.isValidIp(""))
    }

    @Test
    fun valid_ipv6() {
        assertTrue(IpValidator.isValidIp("::1"))
        assertTrue(IpValidator.isValidIp("2001:db8::1"))
    }

    @Test
    fun valid_domain() {
        assertTrue(IpValidator.isValidDomain("example.com"))
        assertTrue(IpValidator.isValidDomain("a.b.c.example.com"))
        assertTrue(IpValidator.isValidDomain("*.example.com"))
        assertTrue(IpValidator.isValidDomain("sub-domain.example.co.uk"))
    }

    @Test
    fun invalid_domain() {
        assertFalse(IpValidator.isValidDomain("no-tld"))
        assertFalse(IpValidator.isValidDomain(""))
    }
}
