# 发布检查清单

打标签前逐项确认。建议维护者按此清单走一遍。

## 1. 版本与变更记录

- [ ] `app/build.gradle.kts` 中 `versionName` 符合语义化版本
- [ ] `app/build.gradle.kts` 中 `versionCode` 已递增（必须严格大于上一版）
- [ ] `CHANGELOG.md` 的 `Unreleased` 已归档到新版本标题下，并更新比较链接
- [ ] `README.md` 中的版本号 / 特性描述与实际一致

## 2. 构建与质量

- [ ] `./gradlew clean assembleDebug` 通过
- [ ] `./gradlew testDebugUnitTest` 全部通过
- [ ] `./gradlew lintDebug` 无 error（warning 需已知且可接受）
- [ ] `./gradlew assembleRelease` 通过（含 R8 混淆与资源压缩）
- [ ] `app/lint-baseline.xml` 若有新增条目，已确认是刻意为之

## 3. 签名与密钥

- [ ] 使用**专用** release keystore，**不是** debug keystore
- [ ] `keystore.properties`、`*.keystore`、`*.jks` 均未被 git 跟踪
  - 验证：`git ls-files | grep -Ei 'keystore|jks'`，应只出现 `keystore.properties.example`
- [ ] keystore 已离线备份（丢失后无法发布同包名更新）
- [ ] CI Secrets（`SIGNING_KEYSTORE_BASE64` 等）配置正确且未被打印到日志

## 4. 敏感信息排查

- [ ] `git log -p --all | grep -Ei 'password|secret|token|api[_-]?key'` 无命中
- [ ] 无硬编码的内网 IP / 域名 / 个人标识
- [ ] `local.properties` 未入库（已在 `.gitignore`）

## 5. 清单与权限

- [ ] `AndroidManifest.xml` 中每个 `<activity>` / `<service>` / `<receiver>` / `<provider>` 都显式声明了 `android:exported`
- [ ] 权限列表最小化，无残留的调试用权限
- [ ] `android:allowBackup` 与备份规则符合预期，备份内容不含敏感数据
- [ ] FileProvider 的 `android:authorities` 使用 `${applicationId}`，未冲突

## 6. 真机回归（至少覆盖 2 个 API 级别，含 Android 14+）

- [ ] 首次启动写入示例规则，首页状态正确
- [ ] 一键开启 → 弹 VPN 授权 → 状态变「已保护」，通知出现
- [ ] 命中规则的域名解析到指定 IP；未命中走上游
- [ ] TCP 53 场景可用（可用 `dig +tcp @8.8.8.8 example.com` 触发）
- [ ] DoH 上游可切换且生效；UDP 回退正常
- [ ] 规则 CRUD / 批量操作 / 拖拽排序（分别在无筛选与有筛选下各测一次）
- [ ] hosts 导入、导出；大列表（1 万行以上）导入不卡顿不崩溃
- [ ] 订阅添加与更新；断网时给出明确失败提示且不影响已生效规则
- [ ] Quick Settings Tile 可切换状态
- [ ] 开机自启生效（需已授权 VPN）
- [ ] 统计页数字与趋势正常；日志导出 CSV 可正常打开
- [ ] 深色 / 浅色 / 跟随系统三种主题无对比度问题
- [ ] 旋转屏幕、切换语言、分屏等配置变更下不崩溃、不丢状态

## 7. 发布动作

- [ ] 合并到 `main`
- [ ] `git tag -a vX.Y.Z -m "vX.Y.Z"` 并 `git push origin vX.Y.Z`
- [ ] GitHub Actions 构建成功，产物 APK 可安装
- [ ] 草稿 Release 的 Release Notes 已核对，补充真机自测结论
- [ ] 发布后在真机安装 Release 版复测核心链路（debug 与 release 的 R8 行为不同）

## 8. 发布后

- [ ] 关闭已修复的 Issue 并关联版本
- [ ] 更新 README 中的下载链接 / 版本号徽章
- [ ] 观察 24 小时内的崩溃与 Issue 反馈
