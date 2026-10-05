# intellij-taskbar-grouping — Taskbar Ungroup

让每个 IntelliJ 项目窗口在 Windows 任务栏上显示为**独立按钮**（取消合并）的插件。

IDEA 是单进程多窗口应用，所有项目窗口共享进程级 AppUserModelID，任务栏因此合并为一个按钮。本插件在窗口就绪后经 Win32 `SHGetPropertyStoreForWindow` 写入**窗口级唯一 AUMID**（优先级高于进程级），各项目随即独立成组——效果等同 [tbg-lite](https://github.com/xixka/taskbar-grouping) 的 `ungroup` 模式，但只作用于 IDE 自身、无常驻外部进程。

自 0.3.0 起可在 **Settings → Tools → Taskbar Ungroup** 添加其他应用的 `.exe`，让它们的窗口也各自独立（0.4.0 起新窗口实时生效、可选按完整路径精确匹配；不配置则行为与旧版一致）。版本史见 [CHANGELOG.md](CHANGELOG.md)。

## 特性

- 项目窗口打开后毫秒级生效，失败自动重试自愈，无需重启 IDE
- 同项目多窗口（Open in New Window、拖出标签）落入同一组；AUMID 基于项目路径哈希，升级/迁移 IDE 不变
- 外部应用新窗口**显示瞬间即生效**（WinEvent 实时 + 轮询兜底）；移除配置后自动撤销、恢复默认分组
- 只经 Shell 官方跨进程属性接口写窗口属性：**不注入 DLL、不写入目标进程、不起远程线程**；依赖平台自带 JNA
- 仅 Windows；其他平台静默不工作。加 IDE 自身 exe 进列表有防呆警告

## 兼容性

| 项 | 值 |
| --- | --- |
| 版本范围 | 2022.3+（build 223）至 2026.2.3（262.*） |
| 兼容验证 | 常规 CI 验证 ideaIC 2024.1.7；边界 2022.3.3 / 2026.2.3 已实测，按需复验 |
| 运行要求 | Windows + JBR 17 |

## 安装

GitHub → Actions → 最新成功运行 → artifact `idea-taskbar-ungroup-dist`，在 IDE 中 `Settings → Plugins → ⚙ → Install Plugin from Disk...` 后重启。

验证：同时开 2 个以上项目，任务栏出现等量独立按钮（任务栏保持默认合并设置）；`idea.log` 出现 `Taskbar Ungroup: set AUMID ...` 日志。

## 已知限制

- AUMID 只能在窗口显示之后改写，任务栏按钮会瞬时重组一次（Windows 固有行为，通常不可感知）
- 外部应用改写后其任务栏**固定（pin）关联失效**，移除配置并重开窗口即恢复
- UWP/商店应用窗口不支持；管理员权限进程可能拒绝映像名查询（该窗口被跳过）

## 开发

```bash
./gradlew test buildPlugin verifyPlugin
./gradlew runIde        # Windows 本地沙箱
```

CI 三段：构建+测试+Verifier → Windows 实机冒烟（双开 notepad 断言 AUMID）→ 发布 dev release；`release.yml` 手动上架 Marketplace。工程约定、架构地图与红线见 [AGENTS.md](AGENTS.md)。

## License

Apache License 2.0
