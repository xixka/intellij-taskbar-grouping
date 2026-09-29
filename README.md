# intellij-taskbar-grouping — Taskbar Ungroup

让每个 IntelliJ IDEA 项目窗口在 Windows 任务栏上显示为**独立按钮**（取消合并）的插件。

IntelliJ 是单进程多窗口应用，所有项目窗口共享同一个进程级 AppUserModelID，任务栏因此把它们合并成一个按钮。本插件在每个项目窗口就绪后，通过 Win32 `SHGetPropertyStoreForWindow` 给该窗口设置**唯一的窗口级 AppUserModelID**（`PKEY_AppUserModel_ID`），窗口级 AUMID 优先级高于进程级，各项目窗口随即在任务栏独立成组——效果等同于 [tbg-lite](https://github.com/xixka/taskbar-grouping) 的 `ungroup` 模式，但只作用于 IDE 自身，无需常驻外部进程。

## 行为特性

- 仅 **Windows** 生效；Linux/macOS 上静默不工作、无报错（`SystemInfoRt.isWindows` 守卫）。
- 项目窗口打开后自动生效（默认无需重启 IDE；插件安装/启用后需重开窗口一次）。
- 同一项目的 AUMID = `TBG.<产品名(ASCII 安全化)>.<项目路径 SHA-256 前 32 位>`，**基于项目路径哈希、稳定不变**，并满足 Windows 对 AUMID 的官方约束（≤128 字符、不含空格）。
- 关闭项目窗口后对应任务栏按钮随窗口消失；`explorer.exe` 重启后属性随 HWND 保留。
- 窗口就绪采用「Frame 标题 + 进程号 + 可见性」匹配，未就绪时 500ms 重试、上限 20 次。
- 依赖平台自带 JNA（`com.intellij.jna.JnaLoader`），**不向插件 zip 打包 jna.jar**，避免类冲突。

## 兼容性

| 项 | 值 |
| --- | --- |
| sinceBuild | 241（IntelliJ Platform 2024.1+） |
| untilBuild | 262.*（2026.2.3） |
| 验证范围 | Plugin Verifier 双端实测：ideaIC 2024.1.7 + IntelliJ IDEA 2026.2.3 |
| 构建目标 | ideaIC 2024.1.7 |
| 运行要求 | Windows + JBR 17 |

## 安装

从 CI 构建产物获取：GitHub 仓库 → Actions → 最新成功运行 → Artifact `idea-taskbar-ungroup-dist`（即 `idea-taskbar-ungroup-<version>.zip`），在 IDE 中 `Settings → Plugins → ⚙ → Install Plugin from Disk...` 安装后重启。

> 验证方式：同时打开 2 个以上项目窗口，任务栏出现对应数量的独立按钮（任务栏保持默认「合并」设置）。`idea.log` 中有 `Taskbar Ungroup: set AUMID '...' for project '...'` 日志。

## 上架 JetBrains Marketplace

- 插件 ID：`com.xixka.taskbarungroup`（Marketplace 禁止 `com.example.*` 占位 ID）
- 发布通道：`default`，首次上传后需通过 JetBrains 人工审核（约 1–2 个工作日）
- 发布管线：`Actions → Release → Run workflow`（`.github/workflows/release.yml`，`workflow_dispatch` 手动触发），读取仓库密钥 `PUBLISH_TOKEN`（[Marketplace 个人资料页](https://plugins.jetbrains.com/me)生成）执行 `./gradlew publishPlugin`

## 开发

```bash
./gradlew buildPlugin   # 产物在 build/distributions/
./gradlew runIde        # Windows 上本地沙箱验证
```

CI（GitHub Actions，`.github/workflows/ci.yml`）以 `buildPlugin` 作为编译验证：`ubuntu-latest + JDK 17 + setup-gradle`，Gradle 依赖与 ideaIC 发行版均走缓存。

### 版本矩阵（及选型说明）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Gradle Wrapper | 8.10.2 | 实施计划锚定 Gradle 8.x |
| Kotlin | 2.1.0 | KGP 2.1.0 官方测试上限为 Gradle 8.10 |
| IntelliJ Platform Gradle Plugin | 2.9.0 | 在 Gradle 8.x 前提下可用的最新稳定版：2.10+ 要求 Gradle ≥ 8.13，2.13+ 要求 Gradle ≥ 9.0.0，均与上述前提冲突，故取 2.9.0（最低 Gradle 8.6） |
| ideaIC | 2024.1.7 | sinceBuild 241 |
| JVM 工具链 | 17 | 与 241 平台一致 |

## 实现要点

- `win32/Win32.kt`：user32 / kernel32 / shell32 的最小 JNA 声明（`EnumWindows`、`GetWindowThreadProcessId`、`IsWindowVisible`、`GetWindowTextW`、`GetCurrentProcessId`、`SHGetPropertyStoreForWindow`）；`WNDENUMPROC` 声明为 `fun interface` 以支持 Kotlin SAM 转换。
- `win32/Com.kt`：`GUID` / `PROPERTYKEY` / `PROPVARIANT`（仅 `VT_LPWSTR`）结构与 `IPropertyStore` vtable 调用（`SetValue`@6 / `Commit`@7 / `Release`@2）。JNA 通过反射发现结构体的**公共字段**，因此 Kotlin 属性必须标注 `@JvmField`。
- `TaskbarUngroupService.kt`：应用级 `@Service`；EDT 读 Frame 标题 → 后台线程执行 Win32/COM → `Memory.setWideString` 写入 `VT_LPWSTR` → `SetValue + Commit + Release`。
- `TaskbarUngroupStartupActivity.kt`：`ProjectActivity`（`postStartupActivity` 扩展点）。

## 已知限制

- 依赖窗口标题匹配 HWND：若 IDE 处于全屏/演示模式等特殊状态，标题可能变化（代码在每次重试时重新读取标题，正常多窗口场景不受影响）。
- 两个项目窗口标题完全一致时无法区分（默认标题含项目路径，实际很难发生），仅第一个匹配窗口会被应用。
- 同一项目的「拆分窗口」（多 Frame）仅主窗口被设置 AUMID；拆分出的窗口可能仍与主窗口分为两组。
- 极端情况下（20 次重试仍无匹配窗口，约 10 秒）放弃并记录 warn 日志。
- AUMID 仅影响任务栏分组行为，不改变点击跳转/预览等其他任务栏交互。

## License

Apache License 2.0（与 Gradle Wrapper、IntelliJ Platform Gradle Plugin 一致）。
