package com.dnsoverride.app.cache

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DnsCache LRU + TTL 逻辑测试。
 */
class DnsCacheTest {

    @Test
    fun put_then_get_returns_value() {
        val c = DnsCache()
        c.put("a.com", 1, byteArrayOf(1, 2, 3), 60)
        assertArrayEquals(byteArrayOf(1, 2, 3), c.get("a.com", 1))
    }

    @Test
    fun negative_or_zero_ttl_rejected() {
        val c = DnsCache()
        // put 对 ttlSeconds <= 0 直接拒绝存储
        c.put("a.com", 1, byteArrayOf(1), 0)
        c.put("a.com", 1, byteArrayOf(1), -1)
        assertNull(c.get("a.com", 1))
        assertEquals(0, c.size())
    }

    @Test
    fun lru_eviction_respects_max() {
        val c = DnsCache(maxEntries = 2)
        c.put("a.com", 1, byteArrayOf(1), 60)
        c.put("b.com", 1, byteArrayOf(2), 60)
        c.put("c.com", 1, byteArrayOf(3), 60)
        assertNull(c.get("a.com", 1))  // a.com 被淘汰
        assertNotNull(c.get("b.com", 1))
        assertNotNull(c.get("c.com", 1))
    }

    @Test
    fun clear_empties_cache() {
        val c = DnsCache()
        c.put("a.com", 1, byteArrayOf(1), 60)
        c.clear()
        assertEquals(0, c.size())
    }

    @Test
    fun different_qtype_stored_separately() {
        val c = DnsCache()
        c.put("a.com", 1, byteArrayOf(1), 60)  // A
        c.put("a.com", 28, byteArrayOf(2), 60) // AAAA
        assertArrayEquals(byteArrayOf(1), c.get("a.com", 1))
        assertArrayEquals(byteArrayOf(2), c.get("a.com", 28))
    }
}
