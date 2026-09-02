# 贡献指南

感谢你愿意为 DNS Override 做出贡献。

## 开始之前

- 提交 Issue 前请先搜索 [已有 Issue](../../issues)，避免重复。
- **安全漏洞请勿公开提交**，参见 [SECURITY.md](SECURITY.md)。
- 改动较大（新功能、重构、架构调整）时，建议先开 Issue 讨论方案再动手。

## 开发环境

| 工具 | 版本 |
| ---- | ---- |
| JDK | 17（AGP 8.5 要求） |
| Android SDK | compileSdk 34 |
| Gradle | 8.8（已随仓库提供 wrapper） |

```bash
git clone https://github.com/nineaiyu/dns-override.git
cd dns-override
./gradlew assembleDebug
```

> 国内网络加速：`export DNSOVERRIDE_MAVEN_MIRROR=true` 后再构建，
> 会临时切到阿里云 Maven 镜像（仓库默认只使用官方源，保证 CI 与海外可构建）。

## 常用命令

```bash
./build.sh debug     # 构建 debug APK
./build.sh release   # 构建 release APK
./build.sh test      # 单元测试
./build.sh lint      # Android Lint
./build.sh keystore  # 生成专用签名 keystore（首次发布用）
```

## 提交规范

采用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/v1.0.0/)：

```
<type>(<scope>): <subject>

<body>

<footer>
```

**type** 取值：

| type | 含义 | 会出现在 CHANGELOG |
| ---- | ---- | ------------------ |
| `feat` | 新功能 | 是 |
| `fix` | 缺陷修复 | 是 |
| `perf` | 性能优化（无行为变更） | 是 |
| `refactor` | 重构 | 否 |
| `test` | 测试 | 否 |
| `docs` | 文档 | 否 |
| `build` | 构建系统 / 依赖 | 否 |
| `ci` | CI 配置 | 否 |
| `chore` | 其他杂项 | 否 |

**scope** 可选，本项目常用：`vpn`、`rules`、`subscription`、`doh`、`stats`、`ui`、`settings`、`deps`。

**subject** 规则：
- 使用中文祈使句（"修复…"/"新增…"/"优化…"），省略主语
- 不以句号结尾，控制在 50 字以内
- 一句话说清"改了什么"，"为什么"放到 body

示例：

```
fix(vpn): 修复设置项变更时 VPN 未运行却被自动启动

ACTION_RELOAD 原先在隧道未建立时会补启动 VPN，导致用户仅修改缓存大小
也会触发连接。改为仅在隧道已运行时重载。

Closes #42
```

**破坏性变更** 在 footer 写 `BREAKING CHANGE: <说明>`。

### 提交前自检

```bash
./gradlew testDebugUnitTest   # 必须通过
./gradlew lintDebug           # 不能新增 lint error
```

## PR 流程

1. 从 `main` 切分支：`feat/xxx`、`fix/xxx`、`chore/xxx`
2. 小步提交，一个提交只做一件事
3. 推送后开 PR，填写 PR 模板（重点写清**自测步骤**与**影响面**）
4. CI（build / test / lint）必须全绿
5. 至少 1 名维护者 Review 通过后合并，使用 **Squash and merge**

## 代码风格

- 遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)，基础格式由 `.editorconfig` 约束。
- 公开的 **类 / 方法** 必须写 KDoc，说明"做什么"与"为什么这么做"；
  尤其是绕过平台限制、兼容特定 API 级别的"反直觉"代码，**必须**写明原因。
- 新增字符串一律放进 `res/values/strings.xml`，**禁止**在布局或代码中硬编码文案。
- 所有可点击的 `ImageView` / `ImageButton` 必须有 `android:contentDescription`（引用字符串资源）。
- 涉及 `minSdk` 以下 API 的调用必须做版本判断（`Build.VERSION.SDK_INT`），
  或使用 AndroidX 兼容层（`ContextCompat` 等）。

## 测试要求

- 纯逻辑（协议编解码、解析、校验、缓存）必须有 JVM 单元测试。
- 修复缺陷时，补一个能复现该缺陷的测试用例。
- 涉及 `minSdk` 兼容性问题时，请在 PR 描述中注明验证过的 API 级别。

## 发布流程（维护者）

1. 更新 `CHANGELOG.md` 与 `app/build.gradle.kts` 中的 `versionName` / `versionCode`
2. 合并到 `main`，打标签：`git tag -a v2.1.0 -m "v2.1.0"`
3. 推送标签：`git push origin v2.1.0`
4. GitHub Actions 会自动构建签名 APK 并创建 **draft** Release
5. 核对 Release Notes、上传真机自测结论后发布

## 许可证

贡献即表示你同意你的代码以本项目使用的 [MIT 许可证](LICENSE) 发布。
