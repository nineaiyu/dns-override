package com.dnsoverride.app.cache

/**
 * 内存 DNS 响应缓存。LRU 淘汰 + TTL 过期。
 *
 * 线程安全：所有操作通过 @Synchronized 保护。
 */
class DnsCache(private var maxEntries: Int = 1000) {

    private data class Entry(val response: ByteArray, val expiresAt: Long)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(domain: String, qtype: Int): ByteArray? {
        val key = "$domain:$qtype"
        val e = cache[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            cache.remove(key)
            return null
        }
        return e.response
    }

    @Synchronized
    fun put(domain: String, qtype: Int, response: ByteArray, ttlSeconds: Int) {
        if (ttlSeconds <= 0) return
        val key = "$domain:$qtype"
        cache[key] = Entry(response, System.currentTimeMillis() + ttlSeconds * 1000L)
    }

    @Synchronized
    fun clear() = cache.clear()

    @Synchronized
    fun size(): Int = cache.size

    /**
     * 调整容量。用户在设置页拖动「缓存容量」后需要真正生效，
     * 否则新容量要等到进程重启才起作用。缩容时立即淘汰多余条目。
     */
    @Synchronized
    fun resize(newMaxEntries: Int) {
        val newMax = newMaxEntries.coerceIn(MIN_ENTRIES, MAX_ENTRIES)
        if (newMax == maxEntries) return
        maxEntries = newMax
        // removeEldestEntry 只在写入时触发，缩容后需主动淘汰
        while (cache.size > maxEntries) {
            val eldest = cache.entries.firstOrNull() ?: break
            cache.remove(eldest.key)
        }
    }

    /** 当前生效的容量上限。 */
    @Synchronized
    fun maxEntries(): Int = maxEntries

    companion object {
        /**
         * 容量调整的边界值。
         * 这里只做防御性钳制，真正的取值范围（100 ~ 5000）由设置页的 SeekBar 约束。
         */
        const val MIN_ENTRIES = 1
        const val MAX_ENTRIES = 100_000
    }
}
