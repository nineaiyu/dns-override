# 更新日志

本项目所有值得注意的变更都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增
- 项目开源化基础设施：MIT 许可证、Issue / PR 模板、CI 与 Release 流水线、贡献指南、安全政策。
- Gradle Version Catalog 统一依赖版本，`.editorconfig` 统一代码格式。
- release 构建开启 R8 混淆与资源压缩，`proguard-rules.pro` 补全 Gson / OkHttp / 组件保留规则。
- `docs/architecture.md`（架构与设计取舍）、`docs/release-checklist.md`（发布检查清单）。
- 订阅 URL 校验抽为独立工具 `SubscriptionUrl`，可单元测试。
- DNS 缓存容量可在设置变更后即时生效（新增 `resize`）。

### 修复
- **崩溃**：Quick Settings Tile 在 Android 13 及以下点击「开启」会崩溃
  （`startActivityAndCollapse(PendingIntent)` 为 API 34 才有的方法）。
- **崩溃**：Tile 副标题在 Android 9 及以下会崩溃（`Tile.setSubtitle` 为 API 29 才有）。
- **崩溃**：`registerReceiver` 三参重载在 Android 7 及以下会崩溃（改用 `ContextCompat`）。
- **崩溃**：`ConcurrentHashMap.computeIfAbsent` 在 Android 5.x 会崩溃（API 24 才有）。
- **崩溃**：`Context.getColor` 在 Android 5.0/5.1 会崩溃（API 23 才有，改用 `ContextCompat`）。
- 设置项变更时若 VPN 未运行会被误启动。
- 规则编辑对话框在配置变更（旋转屏幕）后崩溃，且校验失败时仍会关闭对话框。
- hosts 导出未区分「屏蔽 / 覆盖 / 直连」动作，屏蔽规则被导出为原 IP。
- 订阅自动更新任务每次启动 App 都被重新排期，导致永远不会真正执行。
- 订阅 URL 校验过于宽松（`startsWith("http")` 会放行 `httpfoo`）。
- 规则页冲突检测在每次条目绑定时全量重算，列表长时呈 O(n²) 卡顿。
- 搜索 / 筛选状态下拖拽排序会把整组顺序打乱（可见下标未映射回真实下标）。
- 查询日志回调在非主线程通知 UI。
- 24 小时趋势数据在 SharedPreferences 中无限增长。
- `ACTION_RELOAD` 会关闭所有在途查询的上游 socket（共享集合导致的竞态）。
- 上游"竞速"实为串行等待，总耗时被累加为 `N × 超时`。
- DoH 客户端每次设置变更都新建 `OkHttpClient`，造成连接池与线程泄漏。
- 规则列表布局中 24dp 拖拽手柄与 36dp 按钮低于无障碍推荐触控面积。

### 变更
- 构建不再内置国内 Maven 镜像（默认官方源），改为 `DNSOVERRIDE_MAVEN_MIRROR=true` 显式开启。
- Gradle 分发地址由第三方镜像改回官方地址。
- `build.sh` 不再硬编码 keystore 口令，改为 CSPRNG 随机生成。

## [2.0.0]

### 新增
- 通用化改造：包名迁移至 `com.dnsoverride.app`，移除 PrintHub 品牌。
- 规则组管理、批量操作、拖拽排序与冲突检测。
- hosts 导入 / 导出（SAF）。
- DNS-over-HTTPS 上游与 UDP 53 并发竞速。
- DNS 缓存（LRU + TTL）。
- 统计面板与日志 CSV 导出。
- Quick Settings Tile、开机自启、主题切换。
- TCP 53 成帧与非 53 端口透明中继。

[Unreleased]: https://github.com/nineaiyu/dns-override/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/nineaiyu/dns-override/releases/tag/v2.0.0
