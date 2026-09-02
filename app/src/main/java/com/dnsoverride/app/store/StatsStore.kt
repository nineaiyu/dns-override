package com.dnsoverride.app.store

import android.content.Context
import android.content.SharedPreferences
import com.dnsoverride.app.model.DomainStat
import com.dnsoverride.app.model.StatsSnapshot
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 累计统计持久化。
 *
 * 设计要点（热路径优化）：
 * - 调用方是 VPN 的多条 worker 线程，每条 DNS 查询都会写统计，因此所有共享状态加锁；
 * - 标量计数（总查询 / 拦截 / 转发 / 缓存命中 / DoH）直接写 SharedPreferences，开销极低；
 * - Map 型数据（24h 趋势、Top 域名）在**内存**中维护，按 [FLUSH_INTERVAL_MS] 或
 *   [FLUSH_MIN_WRITES] 批量落盘。原来每条查询都要把三份 JSON 反序列化→修改→再序列化，
 *   订阅/高频解析场景下这是明显的热点；
 * - 24h 趋势只保留最近 [TREND_HOURS] 个小时桶，旧桶在每次写入时裁剪。
 *   原实现只增不减，运行几天后 SharedPreferences 会持续膨胀。
 *
 * 崩溃最多丢失一个刷新周期内（默认 5 秒）的 Top 域名与趋势计数，标量计数不受影响。
 */
class StatsStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val lock = Any()

    // 内存态 Map（落盘前的权威数据源）
    private val hourlyTotal = LinkedHashMap<String, Long>()
    private val hourlyBlocked = LinkedHashMap<String, Long>()
    private val topBlocked = LinkedHashMap<String, Long>()
    private val topForwarded = LinkedHashMap<String, Long>()
    private var mapsLoaded = false

    private var dirtyWrites = 0
    private var lastFlushAt = 0L

    fun addBlocked(domain: String) {
        bumpScalars(blocked = true)
        synchronized(lock) {
            ensureLoadedLocked()
            bumpMap(topBlocked, domain)
            recordHourlyLocked(isBlocked = true)
            markDirtyLocked()
        }
        maybeFlush()
    }

    fun addForwarded(domain: String, fromCache: Boolean, viaDoh: Boolean) {
        bumpScalars(blocked = false, fromCache = fromCache, viaDoh = viaDoh)
        synchronized(lock) {
            ensureLoadedLocked()
            bumpMap(topForwarded, domain)
            recordHourlyLocked(isBlocked = false)
            markDirtyLocked()
        }
        maybeFlush()
    }

    /**
     * 24 小时查询趋势。返回从旧到新的 [TrendPoint] 列表（固定 [TREND_HOURS] 个槽位），
     * 每项含该小时的查询总量与拦截量。槽位按当前小时回推，跨天自动处理。
     */
    fun hourlyTrend(): List<TrendPoint> {
        val currentHour = currentHourBucket()
        synchronized(lock) {
            ensureLoadedLocked()
            return (0 until TREND_HOURS).map { i ->
                val key = (currentHour - (TREND_HOURS - 1 - i)).toHourKey()
                TrendPoint(
                    total = hourlyTotal[key] ?: 0L,
                    blocked = hourlyBlocked[key] ?: 0L
                )
            }
        }
    }

    fun snapshot(): StatsSnapshot {
        synchronized(lock) {
            ensureLoadedLocked()
            return StatsSnapshot(
                totalQueries = prefs.getLong(KEY_TOTAL, 0),
                blockedCount = prefs.getLong(KEY_BLOCKED, 0),
                forwardedCount = prefs.getLong(KEY_FORWARDED, 0),
                cacheHits = prefs.getLong(KEY_CACHE_HITS, 0),
                dohUsed = prefs.getLong(KEY_DOH_USED, 0),
                topBlockedDomains = topN(topBlocked, TOP_N),
                topForwardedDomains = topN(topForwarded, TOP_N)
            )
        }
    }

    /** 清空所有统计（内存态与持久化一并清理）。 */
    fun reset() {
        synchronized(lock) {
            hourlyTotal.clear()
            hourlyBlocked.clear()
            topBlocked.clear()
            topForwarded.clear()
            mapsLoaded = true
            dirtyWrites = 0
            lastFlushAt = 0L
            prefs.edit().clear().apply()
        }
    }

    /**
     * 立即把内存态落盘。用于 VPN 停止、进程即将退出等关键时点，
     * 避免最后一个刷新周期内的数据丢失。
     */
    fun flush() {
        synchronized(lock) {
            if (!mapsLoaded || dirtyWrites == 0) return
            persistLocked()
        }
    }

    // ----------------------------- 内部实现 -----------------------------

    private fun bumpScalars(blocked: Boolean, fromCache: Boolean = false, viaDoh: Boolean = false) {
        val edit = prefs.edit()
            .putLong(KEY_TOTAL, prefs.getLong(KEY_TOTAL, 0) + 1)
        if (blocked) {
            edit.putLong(KEY_BLOCKED, prefs.getLong(KEY_BLOCKED, 0) + 1)
        } else {
            edit.putLong(KEY_FORWARDED, prefs.getLong(KEY_FORWARDED, 0) + 1)
            if (fromCache) edit.putLong(KEY_CACHE_HITS, prefs.getLong(KEY_CACHE_HITS, 0) + 1)
            if (viaDoh) edit.putLong(KEY_DOH_USED, prefs.getLong(KEY_DOH_USED, 0) + 1)
        }
        edit.apply()
    }

    private fun ensureLoadedLocked() {
        if (mapsLoaded) return
        hourlyTotal.putAll(prefs.getStringMap(KEY_HOURLY))
        hourlyBlocked.putAll(prefs.getStringMap(KEY_HOURLY_BLOCKED))
        topBlocked.putAll(prefs.getStringMap(KEY_BLOCKED_TOP))
        topForwarded.putAll(prefs.getStringMap(KEY_FORWARDED_TOP))
        mapsLoaded = true
    }

    private fun bumpMap(map: LinkedHashMap<String, Long>, key: String) {
        map[key] = (map[key] ?: 0L) + 1
        // 惰性裁剪：超过两倍容量时才整理，避免每条查询都做一次全量排序
        if (map.size > TOP_MAP_MAX * 2) trimMap(map, TOP_MAP_MAX)
    }

    private fun recordHourlyLocked(isBlocked: Boolean) {
        val currentHour = currentHourBucket()
        val key = currentHour.toHourKey()
        hourlyTotal[key] = (hourlyTotal[key] ?: 0L) + 1
        if (isBlocked) {
            hourlyBlocked[key] = (hourlyBlocked[key] ?: 0L) + 1
        }
        // 只保留最近 TREND_HOURS 个小时桶，防止数据无限累积
        val oldest = currentHour - (TREND_HOURS - 1)
        pruneOlderThan(hourlyTotal, oldest)
        pruneOlderThan(hourlyBlocked, oldest)
    }

    private fun pruneOlderThan(map: LinkedHashMap<String, Long>, oldestHour: Long) {
        val it = map.keys.iterator()
        while (it.hasNext()) {
            val hour = it.next().toLongOrNull()
            // 无法解析的脏数据一并丢弃
            if (hour == null || hour < oldestHour) it.remove()
        }
    }

    private fun markDirtyLocked() {
        dirtyWrites++
    }

    private fun maybeFlush() {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            dirtyWrites >= FLUSH_MIN_WRITES || now - lastFlushAt >= FLUSH_INTERVAL_MS
        }
        if (due) flush()
    }

    /** 调用方必须持有 [lock]。 */
    private fun persistLocked() {
        prefs.edit()
            .putString(KEY_HOURLY, gson.toJson(hourlyTotal))
            .putString(KEY_HOURLY_BLOCKED, gson.toJson(hourlyBlocked))
            .putString(KEY_BLOCKED_TOP, gson.toJson(topBlocked))
            .putString(KEY_FORWARDED_TOP, gson.toJson(topForwarded))
            .apply()
        dirtyWrites = 0
        lastFlushAt = System.currentTimeMillis()
    }

    private fun topN(map: LinkedHashMap<String, Long>, n: Int): List<DomainStat> =
        map.entries.sortedByDescending { it.value }
            .take(n)
            .map { DomainStat(it.key, it.value) }

    /** 保留计数最高的 [max] 项。 */
    private fun trimMap(map: LinkedHashMap<String, Long>, max: Int) {
        if (map.size <= max) return
        val kept = map.entries.sortedByDescending { it.value }.take(max)
        map.clear()
        kept.forEach { map[it.key] = it.value }
    }

    private fun SharedPreferences.getStringMap(key: String): Map<String, Long> =
        runCatching {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            gson.fromJson<Map<String, Long>>(getString(key, null), type) ?: emptyMap()
        }.getOrDefault(emptyMap())

    /** 当前整点的时间桶（epoch 小时数），用于 24h 趋势对位。 */
    private fun currentHourBucket(): Long = System.currentTimeMillis() / 3_600_000L

    /** 任意 epoch 小时数 → 存储用的字符串 key。 */
    private fun Long.toHourKey(): String = toString()

    /** 每小时的趋势数据点。 */
    data class TrendPoint(val total: Long, val blocked: Long) {
        val blockedRate: Float
            get() = if (total == 0L) 0f else blocked.toFloat() / total
    }

    companion object {
        private const val PREFS_NAME = "dns_override_stats"
        private const val KEY_TOTAL = "total"
        private const val KEY_BLOCKED = "blocked"
        private const val KEY_FORWARDED = "forwarded"
        private const val KEY_CACHE_HITS = "cache_hits"
        private const val KEY_DOH_USED = "doh_used"
        private const val KEY_BLOCKED_TOP = "top_blocked"
        private const val KEY_FORWARDED_TOP = "top_forwarded"
        private const val KEY_HOURLY = "hourly_total"
        private const val KEY_HOURLY_BLOCKED = "hourly_blocked"

        /** 趋势图与趋势数据的保留小时数。 */
        const val TREND_HOURS = 24

        /** Top 域名列表的展示条数与内存保留上限。 */
        private const val TOP_N = 10
        private const val TOP_MAP_MAX = 50

        /** Map 型数据的落盘节流：距上次落盘超过该间隔，或累积写入达到阈值即落盘。 */
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val FLUSH_MIN_WRITES = 50

        @Volatile private var instance: StatsStore? = null
        fun get(context: Context): StatsStore =
            instance ?: synchronized(this) {
                instance ?: StatsStore(context).also { instance = it }
            }
    }
}
