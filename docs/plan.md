# DNS Override 通用化完善实施计划

> **历史文档（已归档）**
> 这是项目从 PrintHub 内嵌组件重构为通用 App 时的一次性实施计划，
> 文中的任务清单仅作历史参考，**不代表当前待办**。
>
> 当前有效的说明请见：
> - [README.md](../README.md) —— 使用与构建
> - [architecture.md](architecture.md) —— 架构与设计取舍
> - [hosts-format.md](hosts-format.md) —— hosts 格式
> - [release-checklist.md](release-checklist.md) —— 发布流程

> **For agentic workers:** 本计划采用 TDD + 增量提交方式实施。每个 Task 独立可测试。Steps 用 `- [ ]` 标记进度。

**Goal:** 将 PrintHub/dns-override 重构为通用 DNS 拦截 App，移除 PrintHub 品牌，新增 hosts 导入导出、DoH 上游、DNS 缓存、统计面板、Quick Settings Tile、开机自启、日志导出等能力，对齐同类开源项目（Intra/Blokada/DNS66）的通用基础版。

**Architecture:**
- 包名 `com.printhub.dnsoverride` → `com.dnsoverride.app`，App 显示名 "DNS Override"
- 沿用现有 VPN 核心策略（仅 addRoute DNS 服务器 IP，非 DNS 流量不进 TUN），已验证稳定，不重写
- 数据层继续用 SharedPreferences + Gson（规则量小，无需 Room）；统计计数器独立存储
- 新增模块：`doh/`（DoH 客户端）、`cache/`（DNS 缓存）、`hosts/`（hosts 格式解析）、`tile/`、`receiver/`、`ui/settings`、`ui/rules`、`ui/stats`
- 上游解析优先级：本地规则 → DNS 缓存 → DoH（若启用）→ UDP 53（fallback）

**Tech Stack:** Kotlin 1.9.24 / AGP 8.5.2 / Android minSdk 21 / Material 3 / Gson / OkHttp 4.12（DoH）/ coroutines

---

## 文件结构映射

新增 / 修改文件清单（按职责分组，落地时按 Task 顺序创建）：

```
dns-override/app/
├── build.gradle.kts                              # 修改：applicationId/namespace/proguard/依赖
├── proguard-rules.pro                            # 修改：OkHttp/Gson 规则
├── src/main/
│   ├── AndroidManifest.xml                       # 修改：包名、新增 Tile/Boot/FileProvider
│   ├── java/com/dnsoverride/app/                 # 全新包路径（从 com.printhub.dnsoverride 迁移）
│   │   ├── DnsOverrideApp.kt                     # 新增：Application，初始化全局状态
│   │   ├── MainActivity.kt                       # 重构：精简为状态卡片 + 入口按钮 + 最近日志
│   │   ├── service/
│   │   │   ├── DnsVpnService.kt                  # 重构：接入 cache/doh/stats/Settings
│   │   │   └── DnsInterceptor.kt                 # 重构：缓存查询 + DoH 优先 + 白名单
│   │   ├── model/
│   │   │   ├── DnsRule.kt                        # 修改：新增 id/note/ttl/whitelist
│   │   │   ├── RuleGroup.kt                      # 修改：新增 description/createdAt
│   │   │   └── StatsSnapshot.kt                  # 新增：统计数据模型
│   │   ├── store/
│   │   │   ├── RuleStore.kt                      # 修改：迁移旧 prefs key + 新字段
│   │   │   ├── SettingsStore.kt                  # 新增：设置项持久化
│   │   │   └── StatsStore.kt                     # 新增：累计统计持久化
│   │   ├── cache/
│   │   │   └── DnsCache.kt                       # 新增：LRU + TTL 缓存
│   │   ├── doh/
│   │   │   ├── DohClient.kt                      # 新增：RFC 8484 wire format
│   │   │   └── DohProviders.kt                   # 新增：内置 DoH 服务商
│   │   ├── hosts/
│   │   │   ├── HostsParser.kt                    # 新增：hosts 格式解析
│   │   │   └── HostsExporter.kt                  # 新增：导出 hosts 格式
│   │   ├── tile/
│   │   │   └── DnsTileService.kt                 # 新增：Quick Settings Tile
│   │   ├── receiver/
│   │   │   └── BootReceiver.kt                   # 新增：开机自启
│   │   ├── ui/
│   │   │   ├── rules/
│   │   │   │   ├── RulesActivity.kt              # 新增：规则组 + 规则管理
│   │   │   │   └── RuleEditDialog.kt             # 新增：添加/编辑规则对话框
│   │   │   ├── settings/
│   │   │   │   └── SettingsActivity.kt           # 新增：设置页
│   │   │   └── stats/
│   │   │       └── StatsActivity.kt              # 新增：统计页
│   │   └── util/
│   │       ├── DnsProtocol.kt                    # 修改：支持 CNAME/SRV/MX 记录解析（仅日志用）
│   │       ├── IpPacket.kt                       # 保留不动
│   │       └── IpValidator.kt                    # 新增：IP/域名格式校验
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml                 # 修改：精简主页 + 入口卡片
│   │   │   ├── activity_rules.xml                # 新增
│   │   │   ├── activity_settings.xml             # 新增
│   │   │   ├── activity_stats.xml                # 新增
│   │   │   ├── dialog_rule_edit.xml              # 新增
│   │   │   ├── item_rule.xml                     # 修改：增加 edit/delete/toggle
│   │   │   ├── item_log.xml                      # 保留
│   │   │   └── item_group.xml                    # 新增
│   │   ├── values/
│   │   │   ├── strings.xml                       # 修改：通用化文案
│   │   │   ├── colors.xml                        # 修改：通用化配色
│   │   │   └── themes.xml                        # 修改：改 Theme.DnsOverride
│   │   ├── xml/
│   │   │   ├── backup_rules.xml                  # 新增：备份规则
│   │   │   └── file_paths.xml                    # 新增：FileProvider 路径
│   │   └── drawable/                             # 新增图标 ic_tile/ic_settings/ic_stats/ic_rules
│   └── src/test/java/com/dnsoverride/app/        # 测试包路径同步迁移
│       ├── util/DnsProtocolTest.kt               # 保留（更新 import）
│       ├── hosts/HostsParserTest.kt              # 新增
│       ├── cache/DnsCacheTest.kt                 # 新增
│       ├── doh/DohClientTest.kt                  # 新增
│       └── model/DnsRuleTest.kt                  # 新增
└── src/main/assets/
    └── default_rules.json                        # 新增：首次启动内置示例规则（中性）
```

---

## Task 1: 重命名包名与品牌通用化

**Files:**
- 修改: `app/build.gradle.kts`
- 修改: `app/src/main/AndroidManifest.xml`
- 迁移目录: `app/src/main/java/com/printhub/dnsoverride/` → `app/src/main/java/com/dnsoverride/app/`
- 修改: 所有 `.kt` 文件的 `package` 声明和 `import`
- 修改: `app/src/main/res/values/strings.xml`, `themes.xml`, `colors.xml`
- 修改: `app/src/test/java/com/printhub/dnsoverride/util/DnsProtocolTest.kt` → `app/src/test/java/com/dnsoverride/app/util/DnsProtocolTest.kt`
- 新增: `app/src/main/assets/default_rules.json`

- [ ] **Step 1.1: 更新 build.gradle.kts**

修改 `applicationId` 和 `namespace`：

```kotlin
android {
    namespace = "com.dnsoverride.app"
    // ...
    defaultConfig {
        applicationId = "com.dnsoverride.app"
        // ...
    }
}
```

同时新增 OkHttp 依赖（DoH 用）和测试依赖：

```kotlin
dependencies {
    // ...existing...
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

- [ ] **Step 1.2: 创建新包目录并迁移源文件**

```bash
mkdir -p app/src/main/java/com/dnsoverride/app
mkdir -p app/src/test/java/com/dnsoverride/app
# 用 git mv 保留历史
git mv app/src/main/java/com/printhub/dnsoverride/MainActivity.kt app/src/main/java/com/dnsoverride/app/MainActivity.kt
# ... 其余文件同样迁移到对应子目录
```

每个 `.kt` 文件顶部 `package com.printhub.dnsoverride.xxx` → `package com.dnsoverride.app.xxx`，`import com.printhub.dnsoverride...` → `import com.dnsoverride.app...`。

- [ ] **Step 1.3: 更新 AndroidManifest.xml**

`android:name=".MainActivity"` 仍指向当前包内 MainActivity（namespace 已改，相对路径自动生效）。无需修改 Activity/Service 的 `android:name`。

更新特殊用途描述：

```xml
<property
    android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    android:value="DNS override for development and testing" />
```

- [ ] **Step 1.4: 更新 strings.xml / themes.xml / colors.xml**

```xml
<!-- strings.xml -->
<resources>
    <string name="app_name">DNS Override</string>
</resources>
```

```xml
<!-- themes.xml -->
<style name="Theme.DnsOverride" parent="Theme.Material3.DayNight.NoActionBar">
    <item name="colorPrimary">@color/brand_primary</item>
    <item name="colorOnPrimary">@android:color/white</item>
    <item name="android:statusBarColor" tools:targetApi="l">@color/brand_primary</item>
</style>
```

`AndroidManifest.xml` 中 `android:theme="@style/Theme.PrintHubDns"` → `@style/Theme.DnsOverride`。

- [ ] **Step 1.5: 更新通知文案与 SESSION_NAME**

`DnsVpnService.kt` 中：

```kotlin
private const val SESSION_NAME = "DNS Override"
// buildNotification 中：
.setContentTitle("DNS Override 正在运行")
// ensureChannel 中：
val ch = NotificationChannel(CHANNEL_ID, "DNS Override", ...)
```

- [ ] **Step 1.6: 替换内置 PrintHub 预设为中性示例**

新增 `app/src/main/assets/default_rules.json`：

```json
{
  "name": "示例规则",
  "description": "首次启动写入的示例规则，可自由编辑或删除",
  "rules": [
    { "domain": "example.com", "ip": "127.0.0.1", "note": "示例：拦截 example.com" },
    { "domain": "*.test.local", "ip": "192.168.1.100", "note": "示例：通配符" }
  ]
}
```

修改 `RuleStore.ensureDefaultSeed()`：从 assets 读取 `default_rules.json` 而非硬编码 PrintHub 域名。

- [ ] **Step 1.7: 验证编译**

```bash
cd dns-override && ./gradlew assembleDebug
```

期望：BUILD SUCCESSFUL，无未解析引用。

- [ ] **Step 1.8: 验证测试通过**

```bash
./gradlew testDebugUnitTest
```

期望：DnsProtocolTest 全部通过。

- [ ] **Step 1.9: Commit**

```bash
git add -A && git commit -m "refactor: rebrand to DNS Override, migrate package to com.dnsoverride.app"
```

---

## Task 2: 扩展数据模型与存储迁移

**Files:**
- 修改: `model/DnsRule.kt`
- 修改: `model/RuleGroup.kt`
- 新增: `model/StatsSnapshot.kt`
- 修改: `store/RuleStore.kt`
- 新增: `store/SettingsStore.kt`
- 新增: `store/StatsStore.kt`

- [ ] **Step 2.1: 扩展 DnsRule**

```kotlin
// model/DnsRule.kt
data class DnsRule(
    val id: String = UUID.randomUUID().toString(),
    val domain: String,
    val ip: String,
    val note: String = "",
    val ttl: Int = 60,
    val enabled: Boolean = true,
    /** 若为 true，此域名不命中任何 override，强制走上游（用于例外）。 */
    val whitelist: Boolean = false
) {
    fun matches(queryDomain: String): Boolean {
        val q = queryDomain.lowercase().trimEnd('.')
        val d = domain.lowercase().trimEnd('.')
        return when {
            d.startsWith("*.") -> {
                val base = d.substring(2)
                q == base || q.endsWith(".$base")
            }
            else -> q == d
        }
    }
}
```

- [ ] **Step 2.2: 扩展 RuleGroup**

```kotlin
// model/RuleGroup.kt
data class RuleGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val rules: List<DnsRule> = emptyList(),
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 2.3: 新增 StatsSnapshot**

```kotlin
// model/StatsSnapshot.kt
data class StatsSnapshot(
    val totalQueries: Long,
    val blockedCount: Long,
    val forwardedCount: Long,
    val cacheHits: Long,
    val dohUsed: Long,
    val topBlockedDomains: List<DomainStat>,
    val topForwardedDomains: List<DomainStat>
) {
    val blockedRate: Float get() = if (totalQueries == 0L) 0f else blockedCount.toFloat() / totalQueries
}

data class DomainStat(val domain: String, val count: Long)
```

- [ ] **Step 2.4: 新增 SettingsStore**

```kotlin
// store/SettingsStore.kt
class SettingsStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class UpstreamMode { PLAIN_UDP, DOH }
    enum class ThemeMode(val prefValue: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

    var upstreamMode: UpstreamMode
        get() = UpstreamMode.valueOf(prefs.getString(KEY_UPSTREAM_MODE, UpstreamMode.PLAIN_UDP.name)!!)
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

    var defaultTtl: Int
        get() = prefs.getInt(KEY_DEFAULT_TTL, 60)
        set(v) = prefs.edit().putInt(KEY_DEFAULT_TTL, v).apply()

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
        private const val KEY_THEME = "theme_mode"

        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
```

- [ ] **Step 2.5: 新增 StatsStore**

```kotlin
// store/StatsStore.kt
class StatsStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addBlocked(domain: String) {
        prefs.edit()
            .putLong(KEY_TOTAL, prefs.getLong(KEY_TOTAL, 0) + 1)
            .putLong(KEY_BLOCKED, prefs.getLong(KEY_BLOCKED, 0) + 1)
            .apply()
        incrementDomain(KEY_BLOCKED_TOP, domain)
    }

    fun addForwarded(domain: String, fromCache: Boolean, viaDoh: Boolean) {
        prefs.edit()
            .putLong(KEY_TOTAL, prefs.getLong(KEY_TOTAL, 0) + 1)
            .putLong(KEY_FORWARDED, prefs.getLong(KEY_FORWARDED, 0) + 1)
            .apply()
        if (fromCache) prefs.edit().putLong(KEY_CACHE_HITS, prefs.getLong(KEY_CACHE_HITS, 0) + 1).apply()
        if (viaDoh) prefs.edit().putLong(KEY_DOH_USED, prefs.getLong(KEY_DOH_USED, 0) + 1).apply()
        incrementDomain(KEY_FORWARDED_TOP, domain)
    }

    fun snapshot(): StatsSnapshot {
        val blocked = prefs.getStringMap(KEY_BLOCKED_TOP)
        val forwarded = prefs.getStringMap(KEY_FORWARDED_TOP)
        return StatsSnapshot(
            totalQueries = prefs.getLong(KEY_TOTAL, 0),
            blockedCount = prefs.getLong(KEY_BLOCKED, 0),
            forwardedCount = prefs.getLong(KEY_FORWARDED, 0),
            cacheHits = prefs.getLong(KEY_CACHE_HITS, 0),
            dohUsed = prefs.getLong(KEY_DOH_USED, 0),
            topBlockedDomains = blocked.toList().sortedByDescending { it.second }.take(10)
                .map { DomainStat(it.first, it.second) },
            topForwardedDomains = forwarded.toList().sortedByDescending { it.second }.take(10)
                .map { DomainStat(it.first, it.second) }
        )
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private fun incrementDomain(key: String, domain: String) {
        val map = prefs.getStringMap(key).toMutableMap()
        map[domain] = (map[domain] ?: 0L) + 1
        // 仅保留 Top 50，避免无限增长
        val trimmed = map.entries.sortedByDescending { it.value }.take(50)
            .associate { it.key to it.value }
        prefs.edit().putString(key, Gson().toJson(trimmed)).apply()
    }

    private fun SharedPreferences.getStringMap(key: String): Map<String, Long> =
        runCatching {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            Gson().fromJson(getString(key, null), type) ?: emptyMap()
        }.getOrDefault(emptyMap())

    companion object {
        private const val PREFS_NAME = "dns_override_stats"
        private const val KEY_TOTAL = "total"
        private const val KEY_BLOCKED = "blocked"
        private const val KEY_FORWARDED = "forwarded"
        private const val KEY_CACHE_HITS = "cache_hits"
        private const val KEY_DOH_USED = "doh_used"
        private const val KEY_BLOCKED_TOP = "top_blocked"
        private const val KEY_FORWARDED_TOP = "top_forwarded"
        @Volatile private var instance: StatsStore? = null
        fun get(context: Context): StatsStore =
            instance ?: synchronized(this) {
                instance ?: StatsStore(context).also { instance = it }
            }
    }
}
```

- [ ] **Step 2.6: 在 RuleStore 中加入旧 prefs 迁移**

```kotlin
// store/RuleStore.kt 顶部新增
private const val LEGACY_PREFS_NAME = "printhub_dns_override"

private fun migrateFromLegacyIfNeeded() {
    if (prefs.contains(KEY_GROUPS)) return
    val legacy = prefs.context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    val legacyGroups = legacy.getString(KEY_GROUPS, null) ?: return
    prefs.edit().putString(KEY_GROUPS, legacyGroups).apply()
    legacy.getString(KEY_ACTIVE_GROUP, null)?.let {
        prefs.edit().putString(KEY_ACTIVE_GROUP, it).apply()
    }
}
```

并在 `init` 块或 `get` 工厂中调用一次。

- [ ] **Step 2.7: 验证编译 + 测试**

```bash
./gradlew assembleDebug && ./gradlew testDebugUnitTest
```

- [ ] **Step 2.8: Commit**

```bash
git add -A && git commit -m "feat: extend data model with rule id/note/ttl/whitelist, add SettingsStore and StatsStore"
```

---

## Task 3: 规则管理 UI 增强（CRUD + 批量）

**Files:**
- 新增: `ui/rules/RulesActivity.kt`
- 新增: `ui/rules/RuleEditDialog.kt`
- 新增: `res/layout/activity_rules.xml`, `res/layout/dialog_rule_edit.xml`, `res/layout/item_group.xml`
- 修改: `res/layout/item_rule.xml`（增加 toggle/edit/delete）
- 修改: `MainActivity.kt`（"管理规则"按钮跳转到 RulesActivity）
- 修改: `res/layout/activity_main.xml`（替换"添加规则"按钮为"管理规则"）

- [ ] **Step 3.1: 创建 activity_rules.xml**

布局：Toolbar + 规则组选择 Tab + RecyclerView（规则列表）+ FAB（添加规则）+ 顶部菜单（编辑组、批量操作、导入/导出）。

```xml
<LinearLayout orientation="vertical">
    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="?attr/colorPrimary"
        app:titleTextColor="@android:color/white"
        app:navigationIcon="@drawable/ic_back" />
    <LinearLayout orientation="horizontal" padding="12dp" gravity="center_vertical">
        <TextView android:id="@+id/textActiveGroup" layout_weight="1" />
        <com.google.android.material.button.MaterialButton
            android:id="@+id/btnSwitchGroup" style="TonalButton" text="切换组" />
    </LinearLayout>
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/listRules" layout_weight="1" />
    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fabAdd" layout_gravity="end|bottom" margin="16dp"
        android:src="@drawable/ic_add" />
</LinearLayout>
```

- [ ] **Step 3.2: 创建 item_rule.xml（含 toggle/edit/delete）**

```xml
<LinearLayout orientation="horizontal" gravity="center_vertical" padding="12dp">
    <com.google.android.material.materialswitch.MaterialSwitch
        android:id="@+id/switchEnabled" layout_weight="0" />
    <LinearLayout orientation="vertical" layout_weight="1" marginStart="12dp">
        <TextView android:id="@+id/textDomain" appearance="BodyLarge" />
        <TextView android:id="@+id/textIp" appearance="BodySmall" />
        <TextView android:id="@+id/textNote" appearance="LabelSmall" textColor="?colorOnSurfaceVariant" />
    </LinearLayout>
    <ImageButton android:id="@+id/btnEdit" src="@drawable/ic_edit" />
    <ImageButton android:id="@+id/btnDelete" src="@drawable/ic_delete" />
</LinearLayout>
```

- [ ] **Step 3.3: 创建 RuleEditDialog**

支持添加/编辑两种模式，字段：domain、ip、note、ttl（可选）、whitelist 复选框。校验：
- 域名格式：`^[a-zA-Z0-9-*]+(\\.[a-zA-Z0-9-*]+)+$` 或 `*.<valid_domain>`
- IP 格式：IPv4 点分四位 / IPv6 冒号分隔

```kotlin
class RuleEditDialog(
    private val initial: DnsRule?,
    private val onSave: (DnsRule) -> Unit
) : DialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?) =
        DialogRuleEditBinding.inflate(inflater, container, false).also { b ->
            b.editDomain.setText(initial?.domain ?: "")
            b.editIp.setText(initial?.ip ?: "")
            b.editNote.setText(initial?.note ?: "")
            b.editTtl.setText((initial?.ttl ?: 60).toString())
            b.checkWhitelist.isChecked = initial?.whitelist == true
            b.btnConfirm.setOnClickListener {
                val domain = b.editDomain.text.toString().trim()
                val ip = b.editIp.text.toString().trim()
                if (!IpValidator.isValidDomain(domain)) {
                    b.editDomain.error = "域名格式无效"; return@setOnClickListener
                }
                if (!b.checkWhitelist.isChecked && !IpValidator.isValidIp(ip)) {
                    b.editIp.error = "IP 格式无效"; return@setOnClickListener
                }
                onSave(initial?.copy(domain = domain, ip = ip, note = b.editNote.text.toString().trim(),
                    ttl = b.editTtl.text.toString().trim().toIntOrNull() ?: 60,
                    whitelist = b.checkWhitelist.isChecked)
                    ?: DnsRule(domain = domain, ip = ip, note = b.editNote.text.toString().trim(),
                        ttl = b.editTtl.text.toString().trim().toIntOrNull() ?: 60,
                        whitelist = b.checkWhitelist.isChecked))
                dismiss()
            }
        }.root
}
```

- [ ] **Step 3.4: 实现 IpValidator**

```kotlin
// util/IpValidator.kt
object IpValidator {
    private val DOMAIN_RE = Regex("^(\\*\\.)?([a-zA-Z0-9-]+\\.)+[a-zA-Z0-9-]+$")
    fun isValidDomain(d: String) = DOMAIN_RE.matches(d) && d.length <= 253
    fun isValidIp(ip: String): Boolean = runCatching {
        InetAddress.getByName(ip)
        // 确保不是被当作主机名解析（如 "1.2.3" 会被补成 1.2.0.0.3）
        ip.contains(".") && ip.split(".").all { it.toIntOrNull() in 0..255 } ||
            ip.contains(":")
    }.getOrDefault(false)
}
```

- [ ] **Step 3.5: 实现 RulesActivity**

支持：
- 切换规则组（顶部按钮 → 弹 Dialog 列表）
- RecyclerView 显示当前组规则，含 toggle/edit/delete
- FAB 添加规则
- 顶部菜单：新建规则组、重命名当前组、删除当前组、批量选择模式（长按进入）
- 批量模式：多选后可批量删除 / 批量启停

规则变更后通过 `LocalBroadcastManager` 或 `LiveData` 通知 `DnsVpnService.reloadRules()`（DnsVpnService 已有 `interceptor.reloadRules()`，新增一个 ACTION_RELOAD intent）。

- [ ] **Step 3.6: 修改 MainActivity 入口**

`activity_main.xml` 中移除"添加规则"按钮，改为"管理规则"：

```xml
<com.google.android.material.button.MaterialButton
    android:id="@+id/btnManageRules"
    android:text="管理规则" />
<com.google.android.material.button.MaterialButton
    android:id="@+id/btnStats"
    android:text="统计" />
<com.google.android.material.button.MaterialButton
    android:id="@+id/btnSettings"
    android:text="设置" />
```

`MainActivity.kt`：

```kotlin
binding.btnManageRules.setOnClickListener {
    startActivity(Intent(this, RulesActivity::class.java))
}
binding.btnStats.setOnClickListener {
    startActivity(Intent(this, StatsActivity::class.java))
}
binding.btnSettings.setOnClickListener {
    startActivity(Intent(this, SettingsActivity::class.java))
}
```

- [ ] **Step 3.7: DnsVpnService 增加 RELOAD action**

```kotlin
// service/DnsVpnService.kt
when (intent?.action) {
    ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
    ACTION_RELOAD -> { interceptor.reloadRules(); return START_STICKY }
    ACTION_START, null -> startVpn()
}
// companion 中：
const val ACTION_RELOAD = "com.dnsoverride.app.RELOAD"
```

RulesActivity 保存规则后：

```kotlin
startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_RELOAD))
```

- [ ] **Step 3.8: 在 AndroidManifest 注册 RulesActivity**

```xml
<activity
    android:name=".ui.rules.RulesActivity"
    android:label="规则管理"
    android:parentActivityName=".MainActivity" />
```

- [ ] **Step 3.9: 验证编译 + 手动验证**

```bash
./gradlew assembleDebug
```

手动验证：安装后能进入规则管理页，能添加/编辑/删除/启停规则，规则变更生效（VPN 状态下查 `printhub.dvcloud.xin` 应按新规则解析）。

- [ ] **Step 3.10: Commit**

```bash
git add -A && git commit -m "feat: full rule CRUD UI with edit/delete/toggle and batch operations"
```

---

## Task 4: 导入/导出 hosts 格式规则

**Files:**
- 新增: `hosts/HostsParser.kt`
- 新增: `hosts/HostsExporter.kt`
- 新增: `res/xml/file_paths.xml`
- 修改: `AndroidManifest.xml`（注册 FileProvider）
- 修改: `ui/rules/RulesActivity.kt`（菜单项 + SAF 调用）

- [ ] **Step 4.1: 实现 HostsParser**

支持两类 hosts 行：
- `0.0.0.0 example.com` / `127.0.0.1 example.com`：广告拦截风格，作为白名单（whitelist=false, ip=0.0.0.0 表示拦截）
- `192.168.1.100 example.com`：DNS 重写风格
- `#` 注释行、空行跳过
- 行内 `#` 后视为注释

```kotlin
// hosts/HostsParser.kt
object HostsParser {
    data class ParsedRule(val domain: String, val ip: String, val note: String = "")

    fun parse(text: String): List<ParsedRule> {
        val out = mutableListOf<ParsedRule>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val ip = parts[0]
            // 多个域名共享同一 IP：`ip domain1 domain2`
            for (i in 1 until parts.size) {
                val domain = parts[i].trim().lowercase()
                if (domain.isEmpty()) continue
                out.add(ParsedRule(domain, ip))
            }
        }
        return out
    }

    /** 是否为广告拦截风格（IP 是 0.0.0.0 / 127.0.0.1）。 */
    fun isAdBlockStyle(ip: String): Boolean =
        ip == "0.0.0.0" || ip == "127.0.0.1"
}
```

- [ ] **Step 4.2: 实现 HostsExporter**

```kotlin
// hosts/HostsExporter.kt
object HostsExporter {
    fun export(group: RuleGroup): String {
        val sb = StringBuilder()
        sb.appendLine("# Exported from DNS Override")
        sb.appendLine("# Group: ${group.name}")
        if (group.description.isNotBlank()) sb.appendLine("# ${group.description}")
        sb.appendLine()
        group.rules.forEach { rule ->
            val ip = if (rule.whitelist) "0.0.0.0" else rule.ip
            val note = if (rule.note.isNotBlank()) "  # ${rule.note}" else ""
            if (rule.enabled) {
                sb.appendLine("$ip ${rule.domain}$note")
            }
        }
        return sb.toString()
    }

    fun exportJson(group: RuleGroup): String = GsonBuilder().setPrettyPrinting().create().toJson(group)
}
```

- [ ] **Step 4.3: 注册 FileProvider**

`res/xml/file_paths.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="exports" path="exports/" />
    <files-path name="exports" path="exports/" />
</paths>
```

`AndroidManifest.xml`：

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

- [ ] **Step 4.4: RulesActivity 实现导入/导出菜单**

使用 SAF（Storage Access Framework）：

```kotlin
// 导入
private val importLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri ->
    if (uri != null) {
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return@registerForActivityResult
        val parsed = HostsParser.parse(text)
        if (parsed.isEmpty()) { showMsg("未解析到任何规则"); return@registerForActivityResult }
        // 询问用户：覆盖当前组 / 新建组
        showImportTargetDialog(parsed, uri.lastPathSegment ?: "导入")
    }
}

// 导出
private val exportLauncher = registerForActivityResult(
    ActivityResultContracts.CreateDocument("text/plain")
) { uri ->
    if (uri != null) {
        val group = store.getActiveGroup() ?: return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
            it.write(HostsExporter.export(group))
        }
        showMsg("已导出 ${group.rules.size} 条规则")
    }
}
```

- [ ] **Step 4.5: 测试 HostsParser**

```kotlin
// src/test/java/com/dnsoverride/app/hosts/HostsParserTest.kt
class HostsParserTest {
    @Test fun parses_simple_line() {
        val r = HostsParser.parse("192.168.1.1 example.com")
        assertEquals(1, r.size)
        assertEquals("example.com", r[0].domain)
        assertEquals("192.168.1.1", r[0].ip)
    }
    @Test fun parses_multiple_domains_per_line() {
        val r = HostsParser.parse("0.0.0.0 ad1.com ad2.com ad3.com")
        assertEquals(3, r.size)
        assertTrue(r.all { it.ip == "0.0.0.0" })
    }
    @Test fun skips_comments_and_blanks() {
        val text = """
            # comment
            <blank>
            127.0.0.1 blocked.com  # inline comment
        """.trimIndent().replace("<blank>", "")
        val r = HostsParser.parse(text)
        assertEquals(1, r.size)
        assertEquals("blocked.com", r[0].domain)
    }
    @Test fun detects_adblock_style() {
        assertTrue(HostsParser.isAdBlockStyle("0.0.0.0"))
        assertFalse(HostsParser.isAdBlockStyle("192.168.1.1"))
    }
}
```

- [ ] **Step 4.6: 验证**

```bash
./gradlew testDebugUnitTest --tests "*HostsParserTest"
./gradlew assembleDebug
```

- [ ] **Step 4.7: Commit**

```bash
git add -A && git commit -m "feat: import/export rules in hosts format with SAF"
```

---

## Task 5: DNS 缓存实现

**Files:**
- 新增: `cache/DnsCache.kt`
- 修改: `service/DnsInterceptor.kt`（查询前查缓存，命中直接返回；上游响应后写入缓存）
- 修改: `service/DnsVpnService.kt`（VPN 启动/规则变更/停止时清空缓存）
- 新增: `res/test/.../cache/DnsCacheTest.kt`

- [ ] **Step 5.1: 实现 DnsCache**

```kotlin
// cache/DnsCache.kt
class DnsCache(private val maxEntries: Int = 1000) {
    private data class Entry(val response: ByteArray, val expiresAt: Long, val qtype: Int)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(domain: String, qtype: Int): ByteArray? {
        val key = "$domain:$qtype"
        val e = cache[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            cache.remove(key); return null
        }
        return e.response
    }

    @Synchronized
    fun put(domain: String, qtype: Int, response: ByteArray, ttlSeconds: Int) {
        if (ttlSeconds <= 0) return
        val key = "$domain:$qtype"
        cache[key] = Entry(response, System.currentTimeMillis() + ttlSeconds * 1000L, qtype)
    }

    @Synchronized
    fun clear() = cache.clear()

    @Synchronized
    fun size(): Int = cache.size
}
```

- [ ] **Step 5.2: DnsInterceptor 接入缓存**

在 `handle(packet)` 入口处插入：

```kotlin
fun handle(packet: ByteArray): ByteArray? {
    // 先尝试缓存
    val question = parseQuestionFromPacket(packet)
    if (question != null && settings.cacheEnabled) {
        val cached = cache.get(question.domain, question.qtype)
        if (cached != null) {
            // 用缓存响应构造回 TUN 的包（重写 Transaction ID 和端点）
            val rebuilt = rebuildFromCacheTemplate(packet, cached, question)
            stats.addForwarded(question.domain, fromCache = true, viaDoh = false)
            return rebuilt
        }
    }
    // 正常处理（命中规则 / 转发上游）
    val resp = handleInternal(packet, question)
    // 命中规则的响应不缓存（规则可能变更），仅缓存上游响应
    if (resp != null && question != null && !ruleMatched) {
        cache.put(question.domain, question.qtype, resp, ttlFromDnsResponse(resp) ?: settings.defaultTtl)
    }
    return resp
}
```

`rebuildFromCacheTemplate`：保留缓存响应的 DNS payload，但用当前请求包的 IP/UDP 端点 + Transaction ID 重写。

- [ ] **Step 5.3: VPN 启动/停止/规则变更时清空缓存**

```kotlin
// DnsVpnService.startVpn() 末尾
interceptor.clearCache()

// DnsVpnService.stopVpn() 末尾
interceptor.clearCache()

// ACTION_RELOAD
interceptor.clearCache()
interceptor.reloadRules()
```

- [ ] **Step 5.4: 测试 DnsCache**

```kotlin
class DnsCacheTest {
    @Test fun put_then_get_returns_value() {
        val c = DnsCache()
        c.put("a.com", 1, byteArrayOf(1, 2, 3), 60)
        assertArrayEquals(byteArrayOf(1, 2, 3), c.get("a.com", 1))
    }
    @Test fun expired_entry_returns_null() {
        val c = DnsCache()
        c.put("a.com", 1, byteArrayOf(1), -1)  // 立即过期
        assertNull(c.get("a.com", 1))
    }
    @Test fun lru_eviction_respects_max() {
        val c = DnsCache(maxEntries = 2)
        c.put("a.com", 1, byteArrayOf(1), 60)
        c.put("b.com", 1, byteArrayOf(2), 60)
        c.put("c.com", 1, byteArrayOf(3), 60)
        assertNull(c.get("a.com", 1))  // a.com 被淘汰
        assertNotNull(c.get("b.com", 1))
        assertNotNull(c.get("c.com", 1))
    }
    @Test fun clear_empties_cache() {
        val c = DnsCache()
        c.put("a.com", 1, byteArrayOf(1), 60)
        c.clear()
        assertEquals(0, c.size())
    }
}
```

- [ ] **Step 5.5: 验证**

```bash
./gradlew testDebugUnitTest --tests "*DnsCacheTest"
./gradlew assembleDebug
```

手动验证：开启 VPN，访问某域名 → 关闭 VPN → 重新开启 → 短时间内再访问同一域名，日志应显示"缓存命中"。

- [ ] **Step 5.6: Commit**

```bash
git add -A && git commit -m "feat: in-memory DNS cache with TTL and LRU eviction"
```

---

## Task 6: DoH 上游支持

**Files:**
- 新增: `doh/DohProviders.kt`
- 新增: `doh/DohClient.kt`
- 修改: `service/DnsInterceptor.kt`（DoH 模式下优先走 DoH，失败 fallback 到 UDP 53）

- [ ] **Step 6.1: 实现 DohProviders**

```kotlin
// doh/DohProviders.kt
data class DohProvider(val name: String, val url: String, val bootstrapIp: String)

object DohProviders {
    val AliDNS = DohProvider("AliDNS", "https://dns.alidns.com/dns-query", "223.5.5.5")
    val Cloudflare = DohProvider("Cloudflare", "https://cloudflare-dns.com/dns-query", "1.1.1.1")
    val Google = DohProvider("Google", "https://dns.google/dns-query", "8.8.8.8")
    val DNSPod = DohProvider("DNSPod", "https://doh.pub/dns-query", "119.29.29.29")

    val all = listOf(AliDNS, Cloudflare, Google, DNSPod)
    fun byUrl(url: String) = all.firstOrNull { it.url == url }
}
```

- [ ] **Step 6.2: 实现 DohClient**

使用 OkHttp 发送 RFC 8484 wire format POST：

```kotlin
// doh/DohClient.kt
class DohClient(
    private val provider: DohProvider,
    private val okHttp: OkHttpClient = defaultClient(provider.bootstrapIp)
) {
    /**
     * 发送 DNS 查询报文，返回 DNS 响应字节。
     * @param dnsQuery 完整 DNS 报文（不含 IP/UDP 头）
     */
    fun query(dnsQuery: ByteArray): ByteArray? = runCatching {
        val req = Request.Builder()
            .url(provider.url)
            .post(dnsQuery.toRequestBody("application/dns-message".toMediaType()))
            .header("Accept", "application/dns-message")
            .build()
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.bytes()
        }
    }.getOrNull()

    companion object {
        /** 关键：用 bootstrap IP 直连 DoH 服务器，避免 DNS 解析 DoH 域名时形成回环。 */
        private fun defaultClient(bootstrapIp: String): OkHttpClient {
            val dns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByAddress(hostname, InetAddress.getByName(bootstrapIp).address))
            }
            return OkHttpClient.Builder()
                .dns(dns)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
        }
    }
}
```

- [ ] **Step 6.3: DnsInterceptor 集成 DoH**

在 `forwardToUpstreamUdp` 之前先尝试 DoH（如果 Settings 启用）：

```kotlin
private fun forwardUpstream(packet: ByteArray, dnsOffset: Int, dnsLen: Int): ByteArray? {
    val dnsPayload = packet.copyOfRange(dnsOffset, dnsOffset + dnsLen)

    // 1. DoH 优先（若启用）
    if (settings.upstreamMode == SettingsStore.UpstreamMode.DOH && dohClient != null) {
        val dohResp = dohClient?.query(dnsPayload)
        if (dohResp != null) {
            stats.addForwarded(currentDomain, fromCache = false, viaDoh = true)
            return wrapUpstreamResponse(packet, dohResp, dohResp.size)
        }
        Log.w(TAG, "DoH failed, fallback to UDP 53")
    }

    // 2. UDP 53 fallback
    return forwardToUpstreamUdp(packet, dnsOffset, dnsLen)
}
```

- [ ] **Step 6.4: DnsVpnService 初始化 DoH 客户端**

```kotlin
// service/DnsVpnService.kt
private fun startVpn() {
    // ...
    interceptor.reloadRules()
    val settings = SettingsStore.get(this)
    if (settings.upstreamMode == SettingsStore.UpstreamMode.DOH) {
        val provider = DohProviders.byUrl(settings.dohProviderUrl) ?: DohProviders.AliDNS
        interceptor.enableDoh(DohClient(provider))
    } else {
        interceptor.disableDoh()
    }
    // ...
}
```

- [ ] **Step 6.5: 测试 DohClient（用 MockWebServer）**

```kotlin
class DohClientTest {
    @Test fun sends_wire_format_post() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(byteArrayOf(1, 2, 3).toResponseBody()))
        server.start()
        val provider = DohProvider("test", server.url("/dns-query").toString(), "127.0.0.1")
        val client = DohClient(provider, OkHttpClient())
        val resp = client.query(byteArrayOf(0xAB, 0xCD))
        assertNotNull(resp)
        assertArrayEquals(byteArrayOf(1, 2, 3), resp)

        val recordedReq = server.takeRequest()
        assertEquals("POST", recordedReq.method)
        assertEquals("application/dns-message", recordedReq.getHeader("Content-Type"))
        assertEquals("application/dns-message", recordedReq.getHeader("Accept"))
    }

    @Test fun returns_null_on_http_error() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        val provider = DohProvider("test", server.url("/dns-query").toString(), "127.0.0.1")
        val client = DohClient(provider, OkHttpClient())
        assertNull(client.query(byteArrayOf(1)))
    }
}
```

- [ ] **Step 6.6: 验证**

```bash
./gradlew testDebugUnitTest --tests "*DohClientTest"
./gradlew assembleDebug
```

手动验证：在设置页选择 DoH 上游（AliDNS），开启 VPN，访问未命中规则的域名应能正常解析（日志标记 DoH）。

- [ ] **Step 6.7: Commit**

```bash
git add -A && git commit -m "feat: DNS-over-HTTPS upstream with UDP 53 fallback"
```

---

## Task 7: 统计面板

**Files:**
- 新增: `ui/stats/StatsActivity.kt`
- 新增: `res/layout/activity_stats.xml`
- 修改: `service/DnsInterceptor.kt`（每次命中/转发/缓存调用 StatsStore）
- 修改: `MainActivity.kt`（按钮入口）

- [ ] **Step 7.1: DnsInterceptor 上报统计**

```kotlin
private val stats = StatsStore.get(vpnService)

// 命中规则
private fun onRuleHit(domain: String) {
    stats.addBlocked(domain)
}

// 转发上游
private fun onForwarded(domain: String, fromCache: Boolean, viaDoh: Boolean) {
    stats.addForwarded(domain, fromCache, viaDoh)
}
```

注意：当前 DnsInterceptor 已在 onQuery 回调里更新 hitCount/missCount（通知用），此处把数据同时写入 StatsStore（持久化）。

- [ ] **Step 7.2: activity_stats.xml 布局**

4 个数字卡片（总查询/拦截/转发/缓存命中）+ 命中率进度条 + 两个 Top 10 列表 + 重置按钮：

```xml
<LinearLayout vertical>
    <Toolbar id="toolbar" />
    <GridLayout columnCount="2">
        <CardView><TextView id="textTotal" /></CardView>
        <CardView><TextView id="textBlocked" /></CardView>
        <CardView><TextView id="textForwarded" /></CardView>
        <CardView><TextView id="textCacheHits" /></CardView>
    </GridLayout>
    <ProgressBar id="barBlockedRate" />
    <TextView id="textBlockedRate" />
    <TextView text="拦截域名 Top 10" />
    <RecyclerView id="listTopBlocked" />
    <TextView text="转发域名 Top 10" />
    <RecyclerView id="listTopForwarded" />
    <MaterialButton id="btnReset" text="重置统计" />
</LinearLayout>
```

- [ ] **Step 7.3: StatsActivity**

```kotlin
class StatsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStatsBinding
    private val store by lazy { StatsStore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnReset.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("重置统计")
                .setMessage("确定清空所有统计数据？")
                .setPositiveButton("重置") { _, _ -> store.reset(); refresh() }
                .setNegativeButton("取消", null).show()
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val s = store.snapshot()
        binding.textTotal.text = s.totalQueries.toString()
        binding.textBlocked.text = s.blockedCount.toString()
        binding.textForwarded.text = s.forwardedCount.toString()
        binding.textCacheHits.text = s.cacheHits.toString()
        binding.barBlockedRate.progress = (s.blockedRate * 100).toInt()
        binding.textBlockedRate.text = "命中率 %.1f%%".format(s.blockedRate * 100)
        binding.listTopBlocked.adapter = StatAdapter(s.topBlockedDomains)
        binding.listTopForwarded.adapter = StatAdapter(s.topForwardedDomains)
    }

    private inner class StatAdapter(private val items: List<DomainStat>) :
        RecyclerView.Adapter<StatAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
            VH(ItemStatBinding.inflate(layoutInflater, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            h.b.textDomain.text = items[pos].domain
            h.b.textCount.text = items[pos].count.toString()
        }
        inner class VH(val b: ItemStatBinding) : RecyclerView.ViewHolder(b.root)
    }
}
```

- [ ] **Step 7.4: 注册 StatsActivity 并加菜单项**

```xml
<activity android:name=".ui.stats.StatsActivity" android:parentActivityName=".MainActivity" />
```

- [ ] **Step 7.5: 验证**

```bash
./gradlew assembleDebug
```

手动：开启 VPN，访问若干域名 → 进入统计页 → 数字应增长；Top 10 列表应显示访问的域名。

- [ ] **Step 7.6: Commit**

```bash
git add -A && git commit -m "feat: persistent stats panel with top domains and hit rate"
```

---

## Task 8: Quick Settings Tile

**Files:**
- 新增: `tile/DnsTileService.kt`
- 修改: `AndroidManifest.xml`（注册 TileService）
- 新增: `res/drawable/ic_tile.xml`

- [ ] **Step 8.1: 实现 DnsTileService**

```kotlin
// tile/DnsTileService.kt
class DnsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val running = DnsVpnService.STATE == DnsVpnService.State.RUNNING
        if (running) {
            startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
        } else {
            // Tile 点击无法弹 VPN 权限对话框，需先跳到 Activity 准备权限
            val launchIntent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_REQUEST_VPN, true)
            startActivityAndCollapse(launchIntent)
        }
    }

    private fun updateTile() {
        qsTile?.apply {
            state = if (DnsVpnService.STATE == DnsVpnService.State.RUNNING)
                Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "DNS Override"
            updateTile()
        }
    }
}
```

- [ ] **Step 8.2: AndroidManifest 注册**

```xml
<service
    android:name=".tile.DnsTileService"
    android:label="DNS Override"
    android:icon="@drawable/ic_tile"
    android:exported="true"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
```

- [ ] **Step 8.3: DnsVpnService 状态变化时刷新 Tile**

```kotlin
// DnsVpnService.broadcastState() 末尾
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
    TileService.requestListeningState(this, ComponentName(this, DnsTileService::class.java))
}
```

- [ ] **Step 8.4: MainActivity 处理 EXTRA_REQUEST_VPN**

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    // ...
    if (intent?.getBooleanExtra(EXTRA_REQUEST_VPN, false) == true) {
        binding.switchVpn.isChecked = true
        startVpn()
    }
}
```

- [ ] **Step 8.5: 验证**

```bash
./gradlew assembleDebug
```

手动：下拉通知栏 → 编辑 Quick Settings → 添加 "DNS Override" Tile → 点击切换。

- [ ] **Step 8.6: Commit**

```bash
git add -A && git commit -m "feat: Quick Settings tile to toggle VPN"
```

---

## Task 9: 开机自启

**Files:**
- 新增: `receiver/BootReceiver.kt`
- 修改: `AndroidManifest.xml`（注册 + 权限）

- [ ] **Step 9.1: 实现 BootReceiver**

```kotlin
// receiver/BootReceiver.kt
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsStore.get(context)
        if (!settings.bootAutoStart) return
        // 启动 VPN Service（如果系统已授予 VPN 权限，会直接启动；否则静默失败）
        val startIntent = Intent(context, DnsVpnService::class.java)
            .setAction(DnsVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }
    }
}
```

- [ ] **Step 9.2: AndroidManifest 注册**

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<receiver
    android:name=".receiver.BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

- [ ] **Step 9.3: 验证**

```bash
./gradlew assembleDebug
```

手动：在设置页打开"开机自启" → 重启手机 → VPN 应自动启动（需用户先前已授予 VPN 权限）。

- [ ] **Step 9.4: Commit**

```bash
git add -A && git commit -m "feat: boot auto-start when user enabled in settings"
```

---

## Task 10: 设置页

**Files:**
- 新增: `ui/settings/SettingsActivity.kt`
- 新增: `res/layout/activity_settings.xml`
- 修改: `AndroidManifest.xml`

- [ ] **Step 10.1: activity_settings.xml**

```xml
<LinearLayout vertical>
    <Toolbar id="toolbar" />
    <PreferenceCategory-ish section: 上游 DNS>
        <RadioGroup id="groupUpstream">
            <RadioButton id="radioUdp" text="UDP 53 (默认)" />
            <RadioButton id="radioDoh" text="DNS-over-HTTPS" />
        </RadioGroup>
        <Spinner id="spinnerDohProvider" />
        <TextView id="textDohUrl" />
    </PreferenceCategory>
    <section: DNS 缓存>
        <MaterialSwitch id="switchCache" text="启用 DNS 缓存" />
        <SeekBar id="seekCacheSize" max="5000" />
        <TextView id="textCacheSize" />
    </section>
    <section: 默认 TTL>
        <EditText id="editTtl" inputType="number" />
    </section>
    <section: 行为>
        <MaterialSwitch id="switchBootStart" text="开机自启" />
    </section>
    <section: 主题>
        <RadioGroup id="groupTheme">
            <RadioButton id="radioSystem" text="跟随系统" />
            <RadioButton id="radioLight" text="浅色" />
            <RadioButton id="radioDark" text="深色" />
        </RadioGroup>
    </section>
</LinearLayout>
```

- [ ] **Step 10.2: SettingsActivity**

```kotlin
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { SettingsStore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 上游模式
        when (settings.upstreamMode) {
            SettingsStore.UpstreamMode.PLAIN_UDP -> binding.radioUdp.isChecked = true
            SettingsStore.UpstreamMode.DOH -> binding.radioDoh.isChecked = true
        }
        binding.groupUpstream.setOnCheckedChangeListener { _, id ->
            settings.upstreamMode = when (id) {
                R.id.radioDoh -> SettingsStore.UpstreamMode.DOH
                else -> SettingsStore.UpstreamMode.PLAIN_UDP
            }
            applyUpstreamChange()
        }
        // DoH Provider Spinner
        binding.spinnerDohProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            DohProviders.all.map { it.name }
        )
        val currentProviderIdx = DohProviders.all.indexOfFirst { it.url == settings.dohProviderUrl }
        if (currentProviderIdx >= 0) binding.spinnerDohProvider.setSelection(currentProviderIdx)
        binding.spinnerDohProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                settings.dohProviderUrl = DohProviders.all[pos].url
                binding.textDohUrl.text = DohProviders.all[pos].url
                applyUpstreamChange()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // 缓存
        binding.switchCache.isChecked = settings.cacheEnabled
        binding.switchCache.setOnCheckedChangeListener { _, v -> settings.cacheEnabled = v }
        binding.seekCacheSize.progress = settings.cacheMaxEntries
        binding.textCacheSize.text = "${settings.cacheMaxEntries} 条"
        binding.seekCacheSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = if (p < 100) 100 else p
                settings.cacheMaxEntries = v
                binding.textCacheSize.text = "$v 条"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { applyUpstreamChange() }
        })
        // 默认 TTL
        binding.editTtl.setText(settings.defaultTtl.toString())
        // 开机自启
        binding.switchBootStart.isChecked = settings.bootAutoStart
        binding.switchBootStart.setOnCheckedChangeListener { _, v -> settings.bootAutoStart = v }
        // 主题
        when (settings.themeMode) {
            SettingsStore.ThemeMode.SYSTEM -> binding.radioSystem.isChecked = true
            SettingsStore.ThemeMode.LIGHT -> binding.radioLight.isChecked = true
            SettingsStore.ThemeMode.DARK -> binding.radioDark.isChecked = true
        }
        binding.groupTheme.setOnCheckedChangeListener { _, id ->
            settings.themeMode = when (id) {
                R.id.radioLight -> SettingsStore.ThemeMode.LIGHT
                R.id.radioDark -> SettingsStore.ThemeMode.DARK
                else -> SettingsStore.ThemeMode.SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(
                when (settings.themeMode) {
                    SettingsStore.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    SettingsStore.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    /** 设置项变更后通知 VPN Service 重新加载（如已运行）。 */
    private fun applyUpstreamChange() {
        startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_RELOAD))
    }
}
```

- [ ] **Step 10.3: Application 中应用主题**

```kotlin
// DnsOverrideApp.kt
class DnsOverrideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(
            when (SettingsStore.get(this).themeMode) {
                SettingsStore.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                SettingsStore.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
```

`AndroidManifest.xml`：

```xml
<application android:name=".DnsOverrideApp" ...>
```

- [ ] **Step 10.4: 验证**

```bash
./gradlew assembleDebug
```

手动：切换 DoH Provider → 看 VPN 是否生效；切换主题 → UI 应即时变化。

- [ ] **Step 10.5: Commit**

```bash
git add -A && git commit -m "feat: settings screen with DoH provider, cache, TTL, theme, boot start"
```

---

## Task 11: 日志导出

**Files:**
- 修改: `MainActivity.kt`（菜单项 + 导出逻辑）
- 复用: `res/xml/file_paths.xml`

- [ ] **Step 11.1: MainActivity 增加导出按钮**

```kotlin
private val exportLogLauncher = registerForActivityResult(
    ActivityResultContracts.CreateDocument("text/csv")
) { uri ->
    if (uri != null) {
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
            w.write("timestamp,domain,result,hit\n")
            logs.forEach { l ->
                w.write("${l.timestamp},${l.domain},${l.resultIp},${l.hit}\n")
            }
        }
        showMsg("已导出 ${logs.size} 条日志")
    }
}

// 在 onCreate 中：
binding.btnExportLogs.setOnClickListener {
    exportLogLauncher.launch("dns_override_logs_${System.currentTimeMillis()}.csv")
}
```

- [ ] **Step 11.2: activity_main.xml 增加导出按钮**

在"清空"按钮旁加"导出"按钮。

- [ ] **Step 11.3: 验证**

```bash
./gradlew assembleDebug
```

手动：产生若干查询日志 → 点"导出" → 选保存位置 → 打开 CSV 文件应包含日志。

- [ ] **Step 11.4: Commit**

```bash
git add -A && git commit -m "feat: export query logs to CSV"
```

---

## Task 12: 更新文档

**Files:**
- 重写: `dns-override/README.md`
- 删除/归档: `docs/dns-override-app-spec.md`（移到 `dns-override/docs/legacy-spec.md`，作为历史记录）
- 新增: `dns-override/docs/hosts-format.md`（hosts 格式说明）

- [ ] **Step 12.1: 重写 README.md**

新 README 要点：
- 项目定位：通用 Android DNS 拦截/重写 App，基于 VpnService
- 核心特性：hosts 导入导出、DoH 上游、DNS 缓存、统计面板、Quick Settings Tile、开机自启、日志导出
- 适用场景：开发测试环境切换、内网域名解析、广告拦截（配合 hosts 列表）、DNS 隐私（DoH）
- 截图位置（占位，待补）
- 构建步骤（保留现有内容，更新包名引用）
- 使用说明：开关 VPN、添加规则、导入 hosts 文件、切换 DoH
- hosts 格式说明（链接到 docs/hosts-format.md）
- 限制说明：不处理 DoT/DoH 系统级（853/443）、不处理 IPv6、不处理 ICMP
- License（建议 MIT）
- 致谢：Intra / Blokada / DNS66 等开源项目启发

- [ ] **Step 12.2: 归档旧 spec**

```bash
git mv docs/dns-override-app-spec.md dns-override/docs/legacy-spec.md
```

并在 legacy-spec.md 顶部加注释：

```markdown
> **历史文档**：原始 PrintHub DNS Override 立项文档，保留作参考。
> 当前项目已通用化，参见 [README.md](../README.md) 和 [plan.md](./plan.md)。
```

- [ ] **Step 12.3: 编写 hosts-format.md**

```markdown
# Hosts 格式说明

DNS Override 支持导入标准 hosts 文件格式（与 /etc/hosts 兼容）：

## 格式

每行一条记录，字段以空格或 Tab 分隔：

`<IP> <domain1> [domain2 ...]  # 可选注释`

- IP 为 IPv4 或 IPv6
- 一行可指定多个域名共享同一 IP
- `#` 后的内容视为注释，会被忽略
- 空行被忽略

## 特殊 IP

- `0.0.0.0` 或 `127.0.0.1`：作为拦截（ad-block 风格），导入时标记为 whitelist
- 其他 IP：作为 DNS 重写，导入时直接 override

## 示例

\`\`\`
# 屏蔽广告
0.0.0.0 ad.example.com
0.0.0.0 tracker.example.com

# 内网解析
192.168.1.100 myapp.local
192.168.1.101 api.myapp.local

# 一行多域名
10.0.0.1 a.test b.test c.test
\`\`\`

## 导出

导出时仅包含启用状态的规则，禁用规则被跳过。
```

- [ ] **Step 12.4: Commit**

```bash
git add -A && git commit -m "docs: rewrite README as universal project, archive legacy spec, add hosts format doc"
```

---

## Task 13: 单元测试补充

**Files:**
- 新增: `src/test/java/com/dnsoverride/app/model/DnsRuleTest.kt`
- 新增: `src/test/java/com/dnsoverride/app/hosts/HostsExporterTest.kt`
- 修改: `src/test/java/com/dnsoverride/app/util/DnsProtocolTest.kt`（更新 import）
- 新增: `src/test/java/com/dnsoverride/app/util/IpValidatorTest.kt`

- [ ] **Step 13.1: DnsRuleTest**

```kotlin
class DnsRuleTest {
    @Test fun exact_match_case_insensitive() {
        assertTrue(DnsRule("Example.com", "1.2.3.4").matches("example.com"))
        assertTrue(DnsRule("example.com", "1.2.3.4").matches("EXAMPLE.COM"))
    }
    @Test fun wildcard_matches_subdomain_and_base() {
        val r = DnsRule("*.example.com", "1.2.3.4")
        assertTrue(r.matches("example.com"))
        assertTrue(r.matches("a.example.com"))
        assertTrue(r.matches("a.b.example.com"))
        assertFalse(r.matches("notexample.com"))
    }
    @Test fun trailing_dot_normalized() {
        assertTrue(DnsRule("example.com", "1.2.3.4").matches("example.com."))
    }
    @Test fun whitelist_rule_still_matches_domain() {
        // whitelist 影响行为但不影响 matches
        val r = DnsRule("example.com", "0.0.0.0", whitelist = true)
        assertTrue(r.matches("example.com"))
    }
}
```

- [ ] **Step 13.2: IpValidatorTest**

```kotlin
class IpValidatorTest {
    @Test fun valid_ipv4() {
        assertTrue(IpValidator.isValidIp("192.168.1.1"))
        assertTrue(IpValidator.isValidIp("8.8.8.8"))
        assertTrue(IpValidator.isValidIp("0.0.0.0"))
    }
    @Test fun invalid_ipv4() {
        assertFalse(IpValidator.isValidIp("999.1.1.1"))
        assertFalse(IpValidator.isValidIp("1.2.3"))
        assertFalse(IpValidator.isValidIp("abc"))
    }
    @Test fun valid_ipv6() {
        assertTrue(IpValidator.isValidIp("::1"))
        assertTrue(IpValidator.isValidIp("2001:db8::1"))
    }
    @Test fun valid_domain() {
        assertTrue(IpValidator.isValidDomain("example.com"))
        assertTrue(IpValidator.isValidDomain("a.b.c.example.com"))
        assertTrue(IpValidator.isValidDomain("*.example.com"))
        assertTrue(IpValidator.isValidDomain("sub-domain.example.co.uk"))
    }
    @Test fun invalid_domain() {
        assertFalse(IpValidator.isValidDomain("no-tld"))
        assertFalse(IpValidator.isValidDomain(""))
        assertFalse(IpValidator.isValidDomain("-bad.com"))
    }
}
```

- [ ] **Step 13.3: HostsExporterTest**

```kotlin
class HostsExporterTest {
    @Test fun exports_enabled_rules_only() {
        val g = RuleGroup(name = "test", rules = listOf(
            DnsRule(domain = "a.com", ip = "1.1.1.1", enabled = true),
            DnsRule(domain = "b.com", ip = "2.2.2.2", enabled = false)
        ))
        val text = HostsExporter.export(g)
        assertTrue(text.contains("1.1.1.1 a.com"))
        assertFalse(text.contains("2.2.2.2"))
    }
    @Test fun whitelist_uses_zero_ip() {
        val g = RuleGroup(name = "test", rules = listOf(
            DnsRule(domain = "ad.com", ip = "0.0.0.0", whitelist = true)
        ))
        val text = HostsExporter.export(g)
        assertTrue(text.contains("0.0.0.0 ad.com"))
    }
}
```

- [ ] **Step 13.4: 更新 DnsProtocolTest 的 import**

```kotlin
// 原：package com.printhub.dnsoverride.util
// 改：package com.dnsoverride.app.util
```

- [ ] **Step 13.5: 验证全部测试**

```bash
./gradlew testDebugUnitTest
./gradlew lint
```

期望：所有测试通过，无新增 lint error。

- [ ] **Step 13.6: Commit**

```bash
git add -A && git commit -m "test: add tests for DnsRule/IpValidator/HostsExporter, update package refs"
```

---

## 完成验证清单

实施完毕后，全部通过以下验证：

- [ ] `./gradlew assembleDebug` BUILD SUCCESSFUL
- [ ] `./gradlew testDebugUnitTest` 全部通过
- [ ] `./gradlew lint` 无 error（warning 可接受）
- [ ] 安装到设备后：
  - [ ] App 显示名为 "DNS Override"，无 PrintHub 字样
  - [ ] 首次启动写入示例规则（example.com / *.test.local），无 PrintHub 域名
  - [ ] 主页"管理规则"进入可 CRUD 规则
  - [ ] 导入 hosts 文件 → 创建新组并应用
  - [ ] 导出当前组为 hosts 文件
  - [ ] 设置页切换 DoH（AliDNS）→ 未命中域名能解析
  - [ ] 设置页关闭 DoH → 走 UDP 53
  - [ ] 统计页数字随查询增长
  - [ ] Quick Settings Tile 可切换 VPN
  - [ ] 开启"开机自启" → 重启手机后 VPN 自动启动
  - [ ] 日志导出为 CSV 文件
- [ ] 旧的 PrintHub 偏好（`printhub_dns_override`）能自动迁移到新 key，规则不丢失

---

## 实施顺序建议

按依赖关系排序：

1. **Task 1**（重命名）— 必须先做，后续所有文件路径依赖新包名
2. **Task 2**（数据模型）— 后续 Task 都依赖新字段
3. **Task 3**（规则 UI CRUD）— 用户最直观的改进
4. **Task 13**（单元测试）— 趁核心数据层改动还热，先把测试补齐
5. **Task 4**（导入导出）— 依赖 Task 3 的规则管理 UI
6. **Task 5**（DNS 缓存）
7. **Task 6**（DoH）— 依赖 Task 2 的 SettingsStore
8. **Task 7**（统计）— 依赖 Task 2 的 StatsStore
9. **Task 10**（设置页）— 依赖 Task 2/5/6
10. **Task 8**（Tile）
11. **Task 9**（开机自启）— 依赖 Task 10 的开关
12. **Task 11**（日志导出）
13. **Task 12**（文档）— 最后做，避免反复改
