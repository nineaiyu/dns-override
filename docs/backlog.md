# 后续待办

按优先级列出**尚未落地**的改进项。已完成的项见 [CHANGELOG.md](../CHANGELOG.md)。

## P1 · 国际化收尾

当前布局已 100% 使用字符串资源（`grep 'android:text="[^@]' app/src/main/res/layout/` 结果为 0），
但以下 **Kotlin 内**的用户可见文案仍硬编码中文，是接入多语言的最后障碍：

| 位置 | 文案数 | 建议做法 |
| ---- | ------ | -------- |
| `util/ConflictDetector.kt` | 7 | `Conflict.message` 改为携带类型 + 参数的结构化数据（`type: Type, args: Array<out Any>`），由 UI 层用 `getString(resId, *args)` 渲染 |
| `util/DnsDiagnostics.kt` | 10 | 返回结构化 `DiagnosticEntry(labelRes, args, value)`，由 `SettingsFragment` 转 `getString` |
| `store/SubscriptionUpdater.kt` | 5 | `Result.Skipped/Failed` 改为携带错误码枚举，UI 层映射文案 |
| `data/preset/PresetSubscriptions.kt` | 12 | 预设元数据迁到资源文件（`string-array` + `item`） |

> 约束：`ConflictDetector` 与 `DnsDiagnostics` 均为纯 JVM 类且已有单元测试，
> 改造时**不要**在其中引入 `Context`，保持可测性。

## P1 · 无障碍补强

- [ ] TalkBack 逐页走查，重点验证首页 Hero 卡的开关与规则页多选栏。
- [ ] `fragment_home.xml` 中 `statusPulse` / `statusIcon` 仍是
      `tools:ignore="ContentDescription"` —— 它们是装饰性图标，
      应改为 `android:importantForAccessibility="no"` 而非忽略检查。
- [ ] 状态变化（连接 / 断开）时用 `view.announceForAccessibility()` 或
      `android:accessibilityLiveRegion="polite"` 播报，当前读屏用户听不到状态切换。
- [ ] 触控面积：`imgDrag` 已到 48dp，但 `fragment_settings.xml` 中部分 SeekBar 与
      单选行的行高仍偏紧，建议行内最小高度 48dp。

## P2 · 测试覆盖率

当前 77 个 JVM 测试覆盖了协议 / 解析 / 校验 / 缓存 / 导出 / 冲突检测。
缺口集中在**需要 Android 环境**的类：

- [ ] 引入 Robolectric（`testImplementation("org.robolectric:robolectric:4.13")`）后补充：
  - `RuleStore`：多组 upsert / reorder / 旧版偏好迁移
  - `StatsStore`：标量计数、Top 域名裁剪、24h 桶裁剪、`flush()` 语义
  - `LogBuffer`：订阅即回调、主线程投递、容量上限
- [ ] `DnsInterceptor` 的上游竞速需网络桩：把 `forwardUdpOnce` 抽成接口注入后，
      可用本地 UDP 回环测试胜负与超时分支。
- [ ] `TcpFlowHandler` 的三次握手 / FIN / RST 序列可写成纯字节序列回放测试
      （构造客户端方向段 → 断言回包 flags 与 seq）。

## P2 · 架构与可维护性

- [ ] `DnsVpnService.STATE` / `activeInstance` 两个静态字段是全局可变状态。
      建议引入 `VpnStateRepository`（`StateFlow`），UI / Tile / 诊断统一订阅，
      替代 `ACTION_STATE` 广播 + 轮询。
- [ ] `MainActivity` 用 `show/hide` 管理四个 Fragment 并手动同步两套导航，
      可迁移到 Navigation Component，或至少把 Tab 状态抽成 `ViewModel`，
      消除 `menuClickListener` 委托。
- [ ] `SettingsStore` 的每个 setter 都独立 `apply()`，设置页一次改动可能触发多次写盘。
      可提供 `edit { }` 事务式批量写入。
- [ ] `RuleStore.listGroups()` 每次全量反序列化。规则量上来后（>5 万条）建议迁移到
      Room + 分页，`SubscriptionUpdater` 改为流式解析（当前 `readText()` 会整文件进内存）。
- [ ] 订阅拉取缺少响应体大小上限与 `Content-Length` 预检，
      超大列表可能 OOM。建议给 OkHttp 加 `Interceptor` 截断至 20 MB。

## P3 · 发布增强

- [ ] 接入崩溃上报（需在隐私政策中说明采集范围）。
- [ ] Play Store 发布需补充：隐私政策页、FGS 用途说明材料、数据安全表单。
- [ ] 仓库根目录的 `ic_launcher_new.jpg` / `ic_launcher.svg` 是临时素材，
      建议移入 `art/` 并从 APK 排除。
- [ ] 截图：README 缺少界面截图，建议用 `adb exec-out screencap -p` 采集四页后放入 `docs/images/`。
- [ ] 版本号自动化：`versionCode` 改为 `git rev-list --count HEAD`，
      `versionName` 从最近 tag 推导，避免每次发版手改。

## P3 · 已知但不计划修复

- 非 53 端口 UDP 中继的流表 key 仅含源端口，同端口多流会复用 —— 实际场景中
  TUN 侧由系统分配端口，冲突概率极低；如需修复，把 key 改为 `"srcIp:srcPort"`。
- `TcpFlowHandler` 的 ISN 用 `kotlin.random.Random` 生成，可预测性对本机回环无实际风险；
  若追求严谨可换 `SecureRandom`。
