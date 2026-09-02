# 架构说明

本文说明 DNS Override 的整体结构与关键设计取舍，便于快速理解代码。

## 总体分层

```
UI 层        MainActivity + 4 个 Fragment（home / rules / stats / settings）
             └─ 只做展示与用户输入，所有 IO 走 Dispatchers.IO

服务层       DnsVpnService      —— VpnService，TUN 包循环与分发
             DnsInterceptor     —— DNS 报文级拦截核心（规则 / 缓存 / 上游）
             TcpFlowHandler     —— 用户态 TCP 状态机（TCP 53 成帧 + 非 53 中继）
             UdpRelay           —— 非 53 端口 UDP 透明中继
             SubscriptionWorker —— 每日订阅更新（WorkManager）

数据层       RuleStore / SettingsStore / StatsStore（SharedPreferences + Gson）
             DnsCache（内存 LRU + TTL）

协议层       DnsProtocol（DNS 报文编解码）、IpPacket（IPv4/TCP/UDP 解析与构造）
```

## 流量路径

```
App 发起 DNS 查询
   └─> 系统 resolver 发往 addDnsServer 指定的 8.8.8.8
         └─> addRoute 命中 /32 路由 → 进入 TUN
               ├─ UDP 53 / TCP 53  → DnsInterceptor.resolveMessage()
               │                      规则命中 → 直接构造应答
               │                      未命中   → 缓存 → 上游（DoH 与 UDP 并发竞速）
               ├─ DoT(853)/DoH(443) → TcpFlowHandler / UdpRelay 经 protect() 透明中继
               ├─ ICMP              → 构造 Echo Reply
               └─ 其他流量          → 不进入 TUN，直接走物理网络（零损耗）
```

**关键点：只对若干公共 DNS 服务器 IP 做 `/32` 路由**，普通上网流量完全不经过本 App。

## 关键设计取舍

| 取舍 | 选择 | 原因 |
| ---- | ---- | ---- |
| 规则读取 | 内存快照 + 显式 `reloadRules()` | 每条查询都反序列化全部规则组会成为热点；改一次规则发一次 RELOAD 更划算 |
| 上游策略 | DoH 与多 UDP **并发竞速**，首个有效响应胜出 | 串行逐个超时会把整条查询拖到 `N × 超时` |
| 并发 socket 管理 | 每次查询一个独立 socket 集合 | 共享集合会让 A 查询的"竞速结束"关掉 B 查询在途的 socket |
| 统计落盘 | Map 型数据在内存聚合、按 5s / 50 次批量落盘 | 每条查询三次 JSON 反序列化→序列化是明显热点 |
| 24h 趋势 | 只保留最近 24 个小时桶，写入时裁剪 | 原实现只增不减，运行数日后 SharedPreferences 持续膨胀 |
| 存储方案 | SharedPreferences + Gson，不引入 Room | 规则量级小、无需复杂查询；引入 Room 收益有限但复杂度显著上升 |
| TCP 实现 | 最小状态机（握手 / 数据 / FIN / RST），不做流控与重传 | TUN 是本地环回，内核缓冲充足，实际不丢包 |
| 订阅更新 | `ExistingPeriodicWorkPolicy.KEEP` + 联网约束 | `UPDATE` 会让每次打开 App 都重置周期，高频用户永远等不到执行 |

## 已知限制（设计使然，非缺陷）

- **不处理加密 DNS 内容**：DoT(853) / DoH(443) 仅透明中继，不解密、不做规则匹配。
- **不接管全流量**：应用层绕过系统解析器、直接 `connect()` 到 IP 发起的 DNS 请求无法拦截。
- **仅 IPv4**：未添加 IPv6 路由，IPv6 DNS 场景未覆盖。
- **非 TCP/UDP 流量**：不做处理（ICMP 除外）。

## 兼容性与安全边界

- `minSdk 21`，所有高于 21 的平台 API 调用都做了 `Build.VERSION.SDK_INT` 判断或使用 AndroidX 兼容层。CI 的 `lintDebug` 开启 `abortOnError`，任何新增的 `NewApi` 错误都会让构建失败。
- 订阅 URL 只接受 `http` / `https`，且响应体有大小上限，避免异常输入导致 OOM。
- 签名信息只从 `keystore.properties` 或环境变量读取，仓库内**不含**任何密钥。
