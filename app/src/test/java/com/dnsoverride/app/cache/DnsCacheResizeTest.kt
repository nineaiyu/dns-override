package com.dnsoverride.app.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DnsCache 容量调整测试。
 *
 * 回归点：设置页拖动「缓存容量」后，新容量原本不会生效
 * （容量在构造时固定），必须等到进程重启。
 */
class DnsCacheResizeTest {

    @Test
    fun resize_enlarges_capacity() {
        val c = DnsCache(maxEntries = 2)
        c.resize(100)
        assertEquals(100, c.maxEntries())
        repeat(50) { c.put("d$it.com", 1, byteArrayOf(it.toByte()), 60) }
        assertEquals(50, c.size())
    }

    @Test
    fun resize_shrinks_and_evicts_immediately() {
        val c = DnsCache(maxEntries = 100)
        repeat(20) { c.put("d$it.com", 1, byteArrayOf(it.toByte()), 60) }
        assertEquals(20, c.size())

        c.resize(5)
        assertEquals(5, c.maxEntries())
        // LinkedHashMap 按访问顺序淘汰，缩容后应立即只剩 5 条
        assertEquals(5, c.size())
    }

    @Test
    fun resize_is_clamped_to_valid_range() {
        val c = DnsCache()
        c.resize(1)
        assertEquals(DnsCache.MIN_ENTRIES, c.maxEntries())
        c.resize(999_999)
        assertEquals(DnsCache.MAX_ENTRIES, c.maxEntries())
    }

    @Test
    fun resize_to_same_value_is_noop() {
        val c = DnsCache(maxEntries = 10)
        c.resize(10)
        assertEquals(10, c.maxEntries())
        assertEquals(0, c.size())
    }

    @Test
    fun shrink_then_put_respects_new_capacity() {
        val c = DnsCache(maxEntries = 50)
        repeat(10) { c.put("d$it.com", 1, byteArrayOf(it.toByte()), 60) }
        c.resize(2)
        c.put("new.com", 1, byteArrayOf(99), 60)
        // 新容量 2：最新写入的 new.com 必须命中（写入的字节是 99）
        assertEquals(99, c.get("new.com", 1)?.first()?.toInt())
        // 最早写入的 d0.com 已被 LRU 淘汰
        assertNull(c.get("d0.com", 1))
    }
}
