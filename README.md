<div align="center">

# DNS Override

**基于 Android `VpnService` 的本地 DNS 拦截 / 重写工具**

仅接管 DNS 服务器 IP 的流量 —— 非 DNS 流量零损耗透传，
不拖慢上网速度，也不需要用户态 TCP 栈。

[![CI](https://img.shields.io/badge/CI-GitHub_Actions-blue?logo=githubactions&logoColor=white)](.github/workflows/ci.yml)
[![API](https://img.shields.io/badge/API-21%2B-green.svg)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

---

> ⚠️ **这不是 VPN。** 它不提供匿名性、流量加密或翻墙能力。
> 它是一个本地 DNS 拦截调试工具，适用于开发测试环境切换、内网域名解析、
> 广告拦截（配合 hosts 列表）与 DNS 隐私（DoH）。

## 核心特性

- **一键开关，零损耗** —— `addRoute` 仅接管若干公共 DNS 服务器 IP，普通上网流量完全不进 TUN
- **UDP / TCP 双协议 DNS** —— 完整处理 TCP 53（RFC 1035 §4.2.2 的 2 字节长度前缀成帧）；UDP 响应超 MTU 时置 TC 位，客户端自动转 TCP 重试
- **DoH 上游** —— 内置 AliDNS / Cloudflare / Google / DNSPod（RFC 8484），与 UDP 并发竞速、首个有效响应胜出；bootstrap IP 直连避免解析回环
- **DoT / DoH 透明中继** —— 发往已接管 DNS IP 的 DoT(853)、DoH(443) 流量经 `protected socket` 零修改透传
- **规则管理** —— 多规则组、批量启停、拖拽排序、精确与通配符（`*.example.com`）、自动冲突检测
- **订阅管理** —— 从 URL 订阅远程 hosts / 域名列表，每日后台自动更新，内置常用公开订阅库
- **hosts 导入 / 导出** —— 兼容标准 `/etc/hosts` 格式，走 SAF（系统文件选择器）
- **DNS 缓存** —— LRU + TTL 双重淘汰，容量可调（100 ~ 5000 条），修改即时生效
- **统计面板** —— 累计查询 / 拦截 / 转发 / 缓存命中率、24h 趋势、Top 10 域名、CSV 导出
- **Quick Settings Tile** —— 通知栏下拉即可开关
- **按应用排除** —— 银行 / 游戏类 App 检测到 VPN 会拒绝工作，可将其排除
- **诊断工具** —— 应用内直接测各上游连通性与耗时
- **开机自启 / 主题切换 / 沉浸式 UI** —— Material 3，深浅色自适应

## 快速开始

```bash
git clone https://github.com/nineaiyu/dns-override.git
cd dns-override
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17、Android SDK（compileSdk 34）、`ANDROID_HOME` 已配置。

<details>
<summary>国内网络加速</summary>

仓库默认只使用官方 Maven 源（保证 CI 与海外贡献者可构建）。需要加速时：

```bash
export DNSOVERRIDE_MAVEN_MIRROR=true
./gradlew assembleDebug
```

会临时切到阿里云 Maven 镜像。
</details>

## 构建

### 命令行

```bash
./build.sh              # release APK（无签名配置时回退 debug keystore，仅本地验证）
./build.sh debug        # debug APK
./build.sh test         # 单元测试
./build.sh lint         # Android Lint
./build.sh keystore     # 生成专用 release keystore（随机口令，写入 keystore.properties）
./build.sh clean        # 清理
```

产物位置：

| 类型 | 路径 |
| ---- | ---- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release.apk` |

Release 构建已开启 **R8 混淆 + 资源压缩**；混淆映射在
`app/build/outputs/mapping/release/mapping.txt`，崩溃堆栈需用它还原。

### 签名

签名配置按三级回退：

1. `keystore.properties`（本地，已 gitignore）
2. 环境变量 / `-P` 参数（CI 注入）
3. debug keystore（兜底，**不可用于公开发布**）

```bash
cp keystore.properties.example keystore.properties
# 填入 DNSOVERRIDE_STORE_FILE / DNSOVERRIDE_STORE_PASSWORD
#                  DNSOVERRIDE_KEY_ALIAS / DNSOVERRIDE_KEY_PASSWORD
```

或直接生成一套：`./build.sh keystore`（随机口令，务必离线备份）。

### Android Studio

用 Android Studio 打开项目根目录，等待 Gradle 同步完成后点 ▶ Run。

## 使用方法

### 基本流程

1. 打开 App，首页显示保护状态卡片
2. 首次启动自动写入示例规则（`example.com` / `*.test.local`）
3. 打开开关 → 系统弹窗请求 VPN 权限 → 允许
4. 状态变「已保护」，通知栏出现常驻通知
5. 浏览器访问规则中的域名，应解析到自定义 IP
6. 关闭：再次点击开关、通知栏「停止」、或 Quick Settings Tile

### 规则与订阅

- 「规则」页：添加 / 编辑 / 删除 / 启停规则；长按进入多选做批量操作
- 动作三选一：**覆盖**（解析到指定 IP，需填 IP）、**屏蔽**（返回 0.0.0.0 或 NXDOMAIN）、**直连**（白名单，强制走上游）
- 顶部菜单：新建 / 重命名 / 删除规则组、添加订阅、更新订阅、导入 / 导出
- 「发现订阅」提供常用公开 hosts 列表，一键添加
- 导入 hosts 支持 `IP 域名`、`IP 域名1 域名2`、纯域名列表与注释，详见 [docs/hosts-format.md](docs/hosts-format.md)

### 设置

| 设置项 | 说明 |
| ------ | ---- |
| 上游 DNS | UDP 53 / DNS-over-HTTPS，DoH 失败自动回退 UDP |
| 自定义上游 | 逗号分隔的 IPv4 列表，留空用内置 `8.8.8.8 / 1.1.1.1 / 223.5.5.5` |
| DNS 缓存 | 开关 + 容量（100~5000）+ 应用内默认 TTL + 转发改写 TTL |
| 屏蔽设置 | 是否屏蔽 AAAA；屏蔽返回 0.0.0.0 还是 NXDOMAIN |
| 按应用排除 | 被排除的应用不经过 VPN，修改后自动重建隧道 |
| 诊断 | 「测试解析」测单域名全链路；「测试上游」测各上游连通性与耗时 |
| 行为 | 开机自启、订阅自动更新 |
| 主题 | 跟随系统 / 浅色 / 深色 |

## 技术原理

```
App 发起 DNS 查询
   └─> 系统 resolver 发往 addDnsServer 指定的 8.8.8.8
         └─> addRoute 命中 /32 路由 → 进入 TUN
               ├─ UDP 53 / TCP 53  → DnsInterceptor：规则 → 缓存 → 上游竞速
               ├─ DoT(853)/DoH(443) → TcpFlowHandler / UdpRelay 透明中继
               ├─ ICMP              → 构造 Echo Reply（ping 这些 IP 可用于排障）
               └─ 其他流量          → 不进 TUN，直接走物理网络
```

规则命中时直接在用户态构造 A / AAAA 应答；未命中则走缓存或上游。
上游采用 **DoH 与多个 UDP 服务器并发竞速**，首个有效响应胜出，
避免串行逐个超时把整条查询拖到 `N × 超时`。

转发上游响应时会把 TTL 改写为可配置的短值（默认 10s），
兼顾规则启停的即时生效与系统 resolver 的短暂缓存收益。

`DnsVpnService` 暴露 `ACTION_START` / `ACTION_STOP` / `ACTION_RELOAD` 三个入口，
状态通过 `ACTION_STATE` 广播 + `STATE` 静态字段发布，UI、Tile 与诊断工具据此同步。

分层结构与关键设计取舍见 [docs/architecture.md](docs/architecture.md)。

## 已知限制

> 这些是**设计使然**，不是待修复的缺陷。

- **不解析加密 DNS**：对 DoT(853)、DoH(443) 仅透明中继，不解密、不做规则匹配
- **不接管全流量**：应用层绕过系统解析器、直接 `connect()` 到 IP 发起的 DNS 请求无法拦截
- **仅 IPv4**：未添加 IPv6 路由，IPv6 DNS 场景未覆盖
- **非 TCP/UDP 流量**不做处理（ICMP 除外）
- **不对第三方订阅内容背书**，是否启用请自行甄别

## 项目结构

```
dns-override/
├── app/
│   ├── build.gradle.kts                # 版本目录 + 混淆 + 签名回退
│   ├── proguard-rules.pro              # R8 规则（Gson / OkHttp / 组件）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/default_rules.json    # 首次启动示例规则
│       │   └── java/com/dnsoverride/app/
│       │       ├── service/            # DnsVpnService · DnsInterceptor · TcpFlowHandler
│       │       │                       # UdpRelay · SubscriptionWorker
│       │       ├── model/              # DnsRule · RuleGroup · StatsSnapshot
│       │       ├── store/              # RuleStore · SettingsStore · StatsStore
│       │       ├── cache/DnsCache.kt   # LRU + TTL 缓存
│       │       ├── doh/                # DohClient · DohProviders（RFC 8484）
│       │       ├── hosts/              # HostsParser · HostsExporter
│       │       ├── data/preset/        # 内置订阅预设
│       │       ├── tile/  receiver/    # Quick Settings Tile · 开机自启
│       │       ├── ui/                 # home · rules · stats · settings + 动画工具
│       │       └── util/               # DnsProtocol · IpPacket · IpValidator
│       │                               # ConflictDetector · SubscriptionUrl
│       └── test/                       # JVM 单元测试
├── docs/
│   ├── architecture.md                 # 架构与设计取舍
│   ├── hosts-format.md                 # hosts 格式说明
│   ├── release-checklist.md            # 发布检查清单
│   ├── backlog.md                      # 后续待办
│   └── plan.md                         # 历史实施计划（已归档）
├── .github/
│   ├── workflows/                      # CI · Release
│   └── ISSUE_TEMPLATE/                 # 缺陷报告 · 功能建议
├── build.sh                            # 构建脚本
├── keystore.properties.example         # 签名配置示例
└── gradle/libs.versions.toml           # 版本目录
```

## 发布到 GitHub

仓库地址：<https://github.com/nineaiyu/dns-override>

首次拆分自内部 monorepo（`dns-override/` 子目录）。为避免把父仓库的
提交信息与内部细节带入公开仓库，采用了**全新初始化**而非保留历史的
`git subtree split`：

```bash
# 1. 复制到独立目录（排除构建产物与本地配置）
rsync -a \
  --exclude '.git' --exclude '.gradle' --exclude 'build' \
  --exclude 'local.properties' --exclude '.idea' --exclude '.DS_Store' \
  --exclude 'release.keystore' --exclude 'keystore.properties' \
  /path/to/PrintHub/dns-override/ /path/to/dns-override/

# 2. 初始化并提交
cd /path/to/dns-override
git init -b main
git add -A
# 发布前必查：不应出现任何 keystore / 本地配置
git ls-files | grep -Ei 'keystore|jks|local\.properties'
git commit -m "feat: DNS Override 2.0.0 首次开源发布"

# 3. 创建远端仓库并推送
gh repo create nineaiyu/dns-override --public --source=. --remote=origin --push
```

后续发版流程：

1. **Settings → Branches** 把 `main` 设为保护分支，要求 CI 通过
2. （可选）**Settings → Secrets and variables → Actions** 配置签名 Secrets：
   `SIGNING_KEYSTORE_BASE64`（keystore 的 base64）、
   `SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`
3. 发布：`git tag -a v2.1.0 -m "v2.1.0" && git push origin v2.1.0`，
   Actions 会构建签名 APK 并创建 **draft** Release
4. 按 [docs/release-checklist.md](docs/release-checklist.md) 走一遍发布检查

## 参与贡献

欢迎 Issue 与 PR。提交信息遵循
[Conventional Commits](https://www.conventionalcommits.org/zh-hans/v1.0.0/)，
代码风格、测试要求与评审流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

安全漏洞请勿公开提交，参见 [SECURITY.md](SECURITY.md)。

## 致谢

本项目受以下开源项目启发：

- [Intra](https://github.com/Jigsaw-Code/intra-android) — Google 的 DNS 隐私 App
- [Blokada](https://github.com/blokadaorg/blokada) — 开源广告拦截
- [DNS66](https://github.com/julian-klode/dns66) — 基于 VPN 的 DNS 拦截

## License

[MIT](LICENSE)
