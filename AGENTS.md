# AGENTS.md

面向 AI 编码代理与人类协作者的工程须知。用户视角的产品说明见 [README.md](README.md)。

插件：Windows 任务栏取消分组（IntelliJ Platform 2022.3–2026.2，Kotlin 2.1.0，JNA，无 UI 线程阻塞）。

## 命令与验证标准

```bash
./gradlew test buildPlugin verifyPlugin   # 标准验证三连
./gradlew runIde                          # Windows 本地沙箱
```

任何改动合入前必须全部满足：

1. **编译零警告**（K2 严格按警告审读，0.4.1 起零警告是既定基线，不得回退）
2. 测试全绿（纯逻辑测试必须无时间/随机/文件系统依赖，不引入 flaky）
3. Plugin Verifier 通过：常规仅验证编译目标 ideaIC 2024.1.7；疑似区间不兼容时以 `-PverifyAllIdes=true` 全量验证（边界 ideaIC 2022.3.3 + IntelliJ IDEA 2026.2.3，CI 手动触发勾选 verify-all-ides 同效）
4. push 后 CI 三 job 绿：build（ubuntu）→ windows-smoke（实机双开 notepad 断言 AUMID）→ dev-release

环境提示：`~/.gradle` 在长会话环境可能被外部清理进程回收（OOM-killer + SIGKILL daemon）。gradle 不可用时可用 K2 编译器直连编译+测试（classpath = ideaIC 全 jar + `-Xfriend-paths` 指向 main 输出模拟 test 编译 friend 语义；JUnitCore 跑全量）。

## 测试哲学

- 纯逻辑类（`ExternalAppMatcher`、`TaskbarUngroupSettings` 序列化）保持**平台无关 JUnit4**：CI 秒级反馈，禁止引入平台启动型测试
- **兼容契约用金标测试守护**：`ProjectAumidTest` 以独立重实现对照 AUMID 公式——公式漂移 = 破坏既有用户任务栏身份与固定项关联
- Win32/UI/Service 运行时面本地不可测，由 windows-smoke E2E 覆盖（写端插件与读端 `taskbar-aumid-check.ps1` 互为镜像）

## 架构地图（细节见各文件 KDoc）

- `win32/Win32.kt` — 最小 JNA 声明（shell32 属性接口 + user32/kernel32 外部路径所需）；HWND 外部路径以 `Long` 传递
- `win32/Com.kt` — `GUID/PROPERTYKEY/PROPVARIANT(VT_LPWSTR)` + `IPropertyStore` vtable；Kotlin 结构体字段必须 `@JvmField`（JNA 反射发现公共字段）
- `TaskbarUngroupService.kt` — 应用级服务；AWT 窗口事件即时路径 + 启动期 150ms 挂接轮询 + 500ms×20 自愈重试；按项目复用 AUMID、按窗口幂等
- `ExternalAppWatcher.kt` — 外部应用双通道发现（`SetWinEventHook` 实时 + 2s `EnumWindows` 兜底）+ 停止时清账撤销
- `ExternalAppMatcher.kt` — 匹配纯函数（文件名/完整路径两模式，畸形输入归 null）
- `TaskbarUngroupSettings.kt` — `PersistentStateComponent`（`taskbarUngroup.xml`，非漫游 POJO 状态类）
- `TaskbarUngroupConfigurable.kt` — 设置页；ListDataListener 必须在构造期注册（Settings 框架多次 create/dispose UI，createComponent 内注册会累积）
- `TaskbarUngroupBootstrap.kt` / `TaskbarUngroupProjectListener.kt` — 引导与兜底（见下方兼容红线）

## 硬性契约（改前必读）

1. **AUMID 公式不可改**：`TBG.<产品名ASCII安全化>.<项目路径SHA-256前32位>`（截 128 字符、无空格）。它是跨版本持久身份，金标测试失配即为破坏性变更
2. **兼容下界 223 锁死一批“旧”API，禁止顺手现代化**：
   - `ProjectManagerListener.projectOpened` 已弃用但 ProjectActivity 为 2023.1+，223 不可用（现有 `@Suppress` 有据，勿删）
   - 勿主动调用 `JnaLoader.load`：签名跨版本不兼容（241 为 `load(Logger)`、2026.x 为 `load()`），依赖平台加载 + 重试自愈
   - `PersistentStateComponent` 用经典 POJO 而非 `BaseState`（list 导入路径在编译目标 241 下不可解析）
3. **不向插件 zip 打包 jna.jar**（用平台自带 JNA，避免类冲突）
4. **IDE 窗口 HWND 一律 AWT peer 直取**（`Native.getComponentID`）；禁止标题枚举匹配（同名项目窗口标题相同，会误绑）
5. **`ExternalAppWatcher` 并发不变量**：停止时快照/清账段与 sweep 同持 `sweepLock`，`applyToWindow` 有 `future == null` 停止守卫——改动 watcher 时必须保持“清账后不再写入”
6. **安全边界**：只经 `SHGetPropertyStoreForWindow` 写窗口属性；不注入、不写目标进程、不起远程线程；WinEvent 为只读 out-of-context 订阅，在 EDT 注册但回调微秒级转后台

## 版本矩阵依据（build.gradle.kts 选型勿“升级”而不核对）

| 组件 | 版本 | 约束 |
| --- | --- | --- |
| Gradle Wrapper | 8.10.2 | 计划锚定 8.x |
| Kotlin/KGP | 2.1.0 | 官方测试上限为 Gradle 8.10 |
| IntelliJ Platform Gradle Plugin | 2.9.0 | 2.10+ 要求 Gradle ≥8.13、2.13+ 要求 ≥9.0，与 8.x 冲突 |
| ideaIC | 2024.1.7 | sinceBuild 241；边界 2022.3.3/2026.2.3 按需全量验证（-PverifyAllIdes） |
| JVM 工具链 | 17 | 与 241 一致 |

## CI 与发布

- `ci.yml`（push master）：build（常规仅验证编译目标 2024.1.7；Gradle 缓存走 setup-gradle 内建，勿再自建 IDE 分发缓存——会与内建重复、曾致缓存总量超 10GB 驱逐抖动）→ windows-smoke → dev-release（dev 产物发布到 GitHub Releases）；手动触发可勾选 verify-all-ides 全量验证区间边界
- `release.yml`（手动 `workflow_dispatch`）：`verifyPlugin publishPlugin` 上架 Marketplace，需仓库密钥 `PUBLISH_TOKEN`；插件 ID `com.xixka.taskbarungroup`
- `plugin.xml` 的 version/change-notes 只在发布流更新；进行中改动记 `CHANGELOG.md` 的「未发布」段

## 代码风格现状

- 项目无 lint 配置，采用 IntelliJ 平台既有风格；ktlint 默认 official 风格与之冲突约 225 处（换行/命名类），**未裁决**——勿零散手改，待整体决定（`.editorconfig` + `intellij_idea` 风格 + 一次性 format 独立提交）
- import 排序按字母序维护（0.4.1 已清零违规）
- 注释/文档/CHANGELOG 用中文；KDoc 记录“为什么”而非复述代码
