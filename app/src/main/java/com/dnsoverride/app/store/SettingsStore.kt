package com.dnsoverride.app.store

import android.content.Context
import com.dnsoverride.app.util.IpValidator

/**
 * 应用设置持久化。所有设置项通过 SharedPreferences 存储。
 */
class SettingsStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class UpstreamMode { PLAIN_UDP, DOH }
    enum class ThemeMode(val prefValue: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

    var upstreamMode: UpstreamMode
        get() = runCatching {
            UpstreamMode.valueOf(prefs.getString(KEY_UPSTREAM_MODE, UpstreamMode.PLAIN_UDP.name)!!)
        }.getOrDefault(UpstreamMode.PLAIN_UDP)
        set(v) = prefs.edit().putString(KEY_UPSTREAM_MODE, v.name).apply()

    var dohProviderUrl: String
        get() = prefs.getString(KEY_DOH_URL, "https://dns.alidns.com/dns-query")!!
        set(v) = prefs.edit().putString(KEY_DOH_URL, v).apply()

    var bootAutoStart: Boolean
        get() = prefs.getBoolean(KEY_BOOT_START, false)
        set(v) = prefs.edit().putBoolean(KEY_BOOT_START, v).apply()

    var cacheEnabled: Boolean
        get() = prefs.getBoolean(KEY_CACHE_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_CACHE_ENABLED, v).apply()

    var cacheMaxEntries: Int
        get() = prefs.getInt(KEY_CACHE_MAX, 1000)
        set(v) = prefs.edit().putInt(KEY_CACHE_MAX, v).apply()

    /** 应用内缓存的有效期（秒）。 */
    var defaultTtl: Int
        get() = prefs.getInt(KEY_DEFAULT_TTL, 60)
        set(v) = prefs.edit().putInt(KEY_DEFAULT_TTL, v).apply()

    /**
     * 转发上游响应时改写的 TTL（秒）。
     * 旧版固定为 0（系统完全不缓存，每次查询都走上游，上游抖动时体验差），
     * 现在默认 10：既保证规则启停能在数秒内生效，又让系统 resolver 能短暂缓存。
     */
    var forwardTtl: Int
        get() = prefs.getInt(KEY_FORWARD_TTL, 10)
        set(v) = prefs.edit().putInt(KEY_FORWARD_TTL, v).apply()

    /** 全局屏蔽 AAAA：IPv6 查询一律返回 NODATA（IPv4-only 环境 / 防止 IPv6 漏网）。 */
    var blockAaaa: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_AAAA, false)
        set(v) = prefs.edit().putBoolean(KEY_BLOCK_AAAA, v).apply()

    /** 屏蔽规则的应答方式：false=0.0.0.0，true=NXDOMAIN。 */
    var blockModeNxdomain: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_MODE_NXDOMAIN, false)
        set(v) = prefs.edit().putBoolean(KEY_BLOCK_MODE_NXDOMAIN, v).apply()

    /** 自定义 UDP 上游 DNS（逗号分隔的 IPv4 列表），留空用内置列表。 */
    var customUpstreams: String
        get() = prefs.getString(KEY_CUSTOM_UPSTREAMS, DEFAULT_UPSTREAMS.joinToString(","))!!
        set(v) = prefs.edit().putString(KEY_CUSTOM_UPSTREAMS, v).apply()

    /** 解析后的上游 DNS 列表（无效项被过滤，全部无效时回退默认）。 */
    fun upstreamServerList(): List<String> {
        val parsed = customUpstreams.split(',', '，', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && IpValidator.isValidIp(it) && !it.contains(':') }
        return parsed.ifEmpty { DEFAULT_UPSTREAMS }
    }

    /** 不参与 VPN（DNS 拦截）的应用包名列表——银行/游戏类 App 检测到 VPN 会拒绝工作。 */
    var excludedApps: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_EXCLUDED_APPS, v.toSet()).apply()

    /** 订阅规则是否自动更新（VPN 启动时检查，超过 24h 自动拉取）。 */
    var subscriptionAutoUpdate: Boolean
        get() = prefs.getBoolean(KEY_SUB_AUTO_UPDATE, true)
        set(v) = prefs.edit().putBoolean(KEY_SUB_AUTO_UPDATE, v).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.values().firstOrNull {
            it.prefValue == prefs.getString(KEY_THEME, ThemeMode.SYSTEM.prefValue)
        } ?: ThemeMode.SYSTEM
        set(v) = prefs.edit().putString(KEY_THEME, v.prefValue).apply()

    companion object {
        private const val PREFS_NAME = "dns_override_settings"
        private const val KEY_UPSTREAM_MODE = "upstream_mode"
        private const val KEY_DOH_URL = "doh_url"
        private const val KEY_BOOT_START = "boot_auto_start"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
        private const val KEY_CACHE_MAX = "cache_max_entries"
        private const val KEY_DEFAULT_TTL = "default_ttl"
        private const val KEY_FORWARD_TTL = "forward_ttl"
        private const val KEY_BLOCK_AAAA = "block_aaaa"
        private const val KEY_BLOCK_MODE_NXDOMAIN = "block_mode_nxdomain"
        private const val KEY_CUSTOM_UPSTREAMS = "custom_upstreams"
        private const val KEY_EXCLUDED_APPS = "excluded_apps"
        private const val KEY_SUB_AUTO_UPDATE = "subscription_auto_update"
        private const val KEY_THEME = "theme_mode"

        val DEFAULT_UPSTREAMS = listOf("8.8.8.8", "1.1.1.1", "223.5.5.5")

        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
