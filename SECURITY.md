# 安全政策

## 支持的版本

仅对最新的主版本提供安全修复。

| 版本 | 是否支持 |
| ---- | -------- |
| 2.x  | 是       |
| < 2.0 | 否      |

## 报告漏洞

请**不要**通过公开 Issue 报告安全漏洞。

请发送邮件至：`security@<YOUR_DOMAIN>`，并包含：

- 漏洞类型与影响面
- 复现步骤（含设备型号 / Android 版本 / App 版本）
- 可用的 PoC 或最小复现工程
- 你认为可能的影响

我们会在 **72 小时内**确认收到，并在 **7 天内**给出初步评估。修复发布前请勿公开披露。

## 本项目的安全边界

DNS Override 是一个**本地 DNS 拦截调试工具**，请注意以下固有限制：

- 它使用 `VpnService` 接管发往公共 DNS 服务器 IP 的流量，**不提供**匿名性、流量加密或翻墙能力，不是隐私 VPN。
- 它**不解析** DoT(853) / DoH(443) 的加密内容，仅做透明中继，因此对这些流量无法应用规则。
- 应用层绕过系统解析器（直接 `connect()` 到 IP 发起 DNS 请求）的流量无法被拦截。
- 第三方订阅列表由社区维护，本项目不对其内容背书，请自行甄别。

## 发布前安全检查清单

- [ ] `keystore.properties`、`*.keystore`、`*.jks` 均未入库（`.gitignore` 已覆盖）
- [ ] `local.properties` 未入库
- [ ] 已通读 `git log -p` 确认无硬编码密钥 / Token / 内网地址
- [ ] release 构建已开启 R8 混淆与资源压缩
- [ ] `android:allowBackup` 与备份规则已按最小化原则配置
- [ ] 组件导出（`android:exported`）均已显式声明且最小化
- [ ] 订阅 URL 强制校验 scheme，限制响应体大小，避免 SSRF 与 OOM
- [ ] 依赖已通过 `./gradlew dependencyUpdates` 或 Dependabot 检查无已知 CVE
