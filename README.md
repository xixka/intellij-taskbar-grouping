# intellij-taskbar-grouping — Taskbar Ungroup

让每个 IntelliJ IDEA 项目窗口在 Windows 任务栏上显示为**独立按钮**（取消合并）的插件。

IntelliJ 是单进程多窗口应用，所有项目窗口共享同一个进程级 AppUserModelID，任务栏因此把它们合并成一个按钮。本插件在每个项目窗口就绪后，通过 Win32 `SHGetPropertyStoreForWindow` 给该窗口设置**唯一的窗口级 AppUserModelID**（`PKEY_AppUserModel_ID`），窗口级 AUMID 优先级高于进程级，各项目窗口随即在任务栏独立成组——效果等同于 [tbg-lite](https://github.com/xixka/taskbar-grouping) 的 `ungroup` 模式，但只作用于 IDE 自身，无需常驻外部进程。

自 0.3.0 起，可在 **设置 → Tools → Taskbar Ungroup** 中添加其他应用的 `.exe`，让这些应用的窗口也各自独立成任务栏按钮（0.4.0 起新窗口实时生效、可选完整路径匹配，详见「外部应用取消分组」）。默认不配置时行为与旧版完全一致。完整版本史见 [CHANGELOG.md](CHANGELOG.md)。

## 行为特性

- 仅 **Windows** 生效；Linux/macOS 上静默不工作、无报错（`SystemInfoRt.isWindows` 守卫）。
- 项目窗口打开后**毫秒级**自动生效（监听 AWT 窗口创建/激活事件，直取 HWND 即时应用；失败自动转入 500ms×20 次自愈重试）。默认无需重启 IDE；插件安装/启用后需重开窗口一次。
- **同一项目的所有窗口共用同一 AUMID**（如 `Open in New Window`、拖出编辑器标签产生的第二个窗口，同样落在该项目的任务栏按钮组内，而非退回 IDE 默认组）。
- 服务经 `AppLifecycleListener.appFrameCreated`（应用首个窗口显示之前发布，公开 API）引导实例化。平台启动时**先显示窗口、后挂接项目**（`IdeProjectFrameAllocator` 中两者并行），插件对无项目窗口做 150ms EDT 轮询，项目挂接即应用——因此 **IDE 启动后的首个项目窗口在加载早期（而非启动完成）即生效**；JNA 未就绪等瞬态失败自动转入重试自愈（不主动调用 `JnaLoader.load`：其签名跨版本不兼容，241 为 `load(Logger)`、2026.x 为无参 `load()`）。
- 同一项目的 AUMID = `TBG.<产品名(ASCII 安全化)>.<项目路径 SHA-256 前 32 位>`，**基于项目路径哈希、稳定不变**，并满足 Windows 对 AUMID 的官方约束（≤128 字符、不含空格）。
- 关闭项目窗口后对应任务栏按钮随窗口消失；`explorer.exe` 重启后属性随 HWND 保留。
- 窗口定位一律经 AWT peer 直取 HWND（`Native.getComponentID`），不做标题枚举匹配——同进程内两个同名项目的窗口标题完全相同，按标题匹配存在误绑定风险；未就绪时 500ms 重试、上限 20 次（启动兜底路径亦然，JNA 迟到不提前放弃）。
- 依赖平台自带 JNA（`com.intellij.jna.JnaLoader`），**不向插件 zip 打包 jna.jar**，避免类冲突。

## 外部应用取消分组（0.3.0+，0.4.0 起实时生效）

**设置 → Tools → Taskbar Ungroup**：点 ➕ 选择应用的 `.exe`（如 `chrome.exe`），Apply 后：

- 该应用的**每个可见顶层窗口获得每窗口唯一的 AUMID**（`TBG.X.<pid>.<hwnd>`），任务栏各自独立成按钮；新开窗口**显示瞬间即被处理**（0.4.0 起经 WinEvent 事件订阅 `EVENT_OBJECT_SHOW` 实时触发；2 秒 `EnumWindows` 轮询兜底，覆盖钩子安装前已存在的窗口与先显示后才有标题的场景）。
- 匹配模式（0.4.0 起可选）：默认按 **exe 文件名**（大小写不敏感，应用升级/换盘不影响生效）；勾选「按完整路径匹配」后精确到安装路径（消除同名不同路径 exe 的误伤）。
- 移除条目（或清空列表）后，已生效窗口清空显式 AUMID 并触发一次窗口边框重算（`SWP_FRAMECHANGED`）促使任务栏立即恢复默认分组；若系统仍维持旧分组，**重开该应用窗口即彻底恢复**。
- 实现途径：WinEvent 订阅（out-of-context，事件经 AWT 消息泵派发）+ `EnumWindows` 轮询 + `GetWindowThreadProcessId`/`QueryFullProcessImageNameW` 归属进程，随后同样走 `SHGetPropertyStoreForWindow` 写窗口属性——**Shell 官方跨进程属性接口，不注入 DLL、不向目标进程写入任何代码（WinEvent 为只读系统事件订阅）、不起远程线程**；自动跳过 IDE 自身进程（项目窗口仍按项目分组）。
- 防呆：把 JetBrains IDE 可执行文件（`idea64.exe` 等）加进列表时设置页会给出警告——那会使 IDE 窗口退化为每窗口独立按钮，与按项目分组语义冲突。

## 兼容性

| 项 | 值 |
| --- | --- |
| sinceBuild | 223（IntelliJ Platform 2022.3+，0.4.0 起自 241 下探） |
| untilBuild | 262.*（2026.2.3） |
| 验证范围 | Plugin Verifier 实测：ideaIC 2022.3.3 + 2024.1.7 + IntelliJ IDEA 2026.2.3 |
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

CI（GitHub Actions，`.github/workflows/ci.yml`，参照 JetBrains 官方插件模板的三段结构）：
- **build**（ubuntu）：`test buildPlugin verifyPlugin`，产物与 Plugin Verifier 报告上传 artifact；
- **windows-smoke**（windows-latest，功能级实测）：预置 `taskbarUngroup.xml`（配置 notepad.exe）→ 沙箱启动真实 IDE → 双开记事本 → 以 `SHGetPropertyStoreForWindow` 读取窗口 AUMID 断言 `TBG.X.` 前缀写入成功（`.github/scripts/taskbar-aumid-check.ps1`，与插件写入端互为镜像）；
- **dev-release**（仅 master 推送，依赖前两 job 通过）：把 build 产物发布到 GitHub `dev` release。

`release.yml` 为手动触发的 Marketplace 上架工作流（`verifyPlugin publishPlugin`）。

### 版本矩阵（及选型说明）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Gradle Wrapper | 8.10.2 | 实施计划锚定 Gradle 8.x |
| Kotlin | 2.1.0 | KGP 2.1.0 官方测试上限为 Gradle 8.10 |
| IntelliJ Platform Gradle Plugin | 2.9.0 | 在 Gradle 8.x 前提下可用的最新稳定版：2.10+ 要求 Gradle ≥ 8.13，2.13+ 要求 Gradle ≥ 9.0.0，均与上述前提冲突，故取 2.9.0（最低 Gradle 8.6） |
| ideaIC | 2024.1.7 | sinceBuild 241 |
| JVM 工具链 | 17 | 与 241 平台一致 |

## 实现要点

- `win32/Win32.kt`：最小 JNA 声明——shell32（`SHGetPropertyStoreForWindow`）+ 外部应用路径所需的 user32/kernel32（`EnumWindows`/`GetWindowThreadProcessId`/`QueryFullProcessImageNameW`/`SetWinEventHook`/`UnhookWinEvent`/`SetWindowPos` 等）。IDE 自身窗口仍经 AWT peer 直取 HWND。HWND 在外部路径以 `Long`（64 位指针值）传递，Windows 侧 JBR 仅 64 位。
- `win32/Com.kt`：`GUID` / `PROPERTYKEY` / `PROPVARIANT`（仅 `VT_LPWSTR`）结构与 `IPropertyStore` vtable 调用（`SetValue`@6 / `Commit`@7 / `Release`@2）。JNA 通过反射发现结构体的**公共字段**，因此 Kotlin 属性必须标注 `@JvmField`。
- `TaskbarUngroupService.kt`：应用级 `@Service`；**即时路径**：AWT `WINDOW_OPENED/ACTIVATED` 事件（EDT）`Native.getComponentID` 直取 HWND → 后台线程 COM 应用；启动期窗口先显示后挂接项目（平台 `IdeProjectFrameAllocator` 并行流程），故对无项目窗口以 `javax.swing.Timer` 150ms 轮询等待挂接（上限 9 秒）；**兜底路径**：`WindowManager.getFrame(project)` 取 Frame 后同样经 peer 直取 HWND → 500ms×20 次窗口级自愈重试（JNA 迟到不提前放弃，成功后记录并停止）；**按窗口幂等、按项目复用 AUMID**（同项目第二个窗口沿用同一 AUMID，任务栏并入该项目按钮组）；`Memory.setWideString` 写入 `VT_LPWSTR` → `SetValue + Commit + Release`。
- `ExternalAppWatcher.kt`：外部应用监视器，双通道窗口发现——**实时**：`SetWinEventHook`（`EVENT_OBJECT_SHOW`，out-of-context + `WINEVENT_SKIPOWNPROCESS`）在 EDT 注册（out-of-context 事件经安装线程消息循环派发，AWT EDT 即常驻消息泵），回调仅过滤 `OBJID_WINDOW` 后转后台处理（微秒级返回，不阻塞 UI 线程）；**兜底**：`scheduleWithFixedDelay` 2 秒全量 `EnumWindows`（微秒级，异常全捕获防周期任务夭折，同时承担撤销 diff）。过滤不可见/无标题/工具窗口/**子窗口**（事件路径独有：`WS_CHILD` 鉴别），跳过 IDE 自身进程；按匹配快照过滤 exe → 每窗口唯一 AUMID（`TBG.X.<pid>.<hwnd>`）；diff 增量应用/撤销（撤销时清空 AUMID + `SWP_FRAMECHANGED` 促任务栏重估），失败重试上限 10 次；钩子安装失败降级为纯轮询（行为同 0.3.x）。
- `ExternalAppMatcher.kt`：匹配规则纯函数（无平台依赖、可单测）——文件名模式（0.3.x 兼容）与完整路径模式（0.4.0 新增）的归一化与匹配。
- `TaskbarUngroupSettings.kt`：应用级 `PersistentStateComponent`（`taskbarUngroup.xml`，非漫游，经典 POJO 状态类——`BaseState` 的 list 导入路径在编译目标下不可解析故未用）；`exeEntries` + `matchFullPath`，归一化匹配快照 = `ExternalAppMatcher.targetsOf(...)`。
- `TaskbarUngroupConfigurable.kt`：Settings → Tools 设置页（`applicationConfigurable`），`JBList` + `ToolbarDecorator` + exe 文件选择器 + 「按完整路径匹配」开关；条目命中 JetBrains IDE 可执行文件黑名单时显示防呆警告；Apply → 持久化并通知服务即时启停监视。
- `TaskbarUngroupBootstrap.kt`：`AppLifecycleListener.appFrameCreated` 引导监听器（`<applicationListeners>` 惰性注册）。该消息由平台在决定打开首个窗口之前同步发布（见 `IdeStarter.openProjectIfNeeded`），因此首个项目窗口 `WINDOW_OPENED` 时 AWT 钩子已就绪，同样走即时路径；构造函数刻意零副作用，实例化安全性与发布时机无关。
- `TaskbarUngroupProjectListener.kt`：`ProjectManagerListener`（`<projectListeners>` 注册，projectOpened 触发兜底路径，并作为引导未生效时的服务实例化保险）。跨版本稳定的公开 API——协程版 ProjectActivity 为 2023.1+ 才引入，兼容下界 2022.3 不可用。

## 已知限制

- 窗口级 AUMID 只能在窗口显示之后改写：任务栏按钮会经历一次瞬时重组（新按钮替代原分组）。后续打开的项目在窗口出现动画之内完成（通常不可感知）；IDE 启动后的首个窗口因平台「先显示后挂项目」在加载早期完成（挂接后毫秒级，早于启动完成）。这是 Windows 任务栏对运行时重分组的固有行为，无法完全消除。
- 极端情况下（窗口级 20 次重试仍失败，约 10 秒）放弃并记录 warn 日志。
- AUMID 仅影响任务栏分组行为，不改变点击跳转/预览等其他任务栏交互。
- 外部应用：改写 AUMID 后该应用的**任务栏固定（pin）关联会失效**（Windows 按默认 AUMID 关联固定项），移除配置并重开窗口后恢复；UWP/商店应用窗口归属 `ApplicationFrameHost.exe`，不支持按其真实应用名匹配；某些以管理员权限运行的进程可能拒绝映像名查询（该窗口被安全跳过）；按文件名匹配意味着同名不同路径的 exe 会同时生效（0.4.0 起可用「按完整路径匹配」模式消除）。

## License

Apache License 2.0（与 Gradle Wrapper、IntelliJ Platform Gradle Plugin 一致）。
