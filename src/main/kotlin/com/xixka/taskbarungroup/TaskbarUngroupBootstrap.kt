package com.xixka.taskbarungroup

import com.intellij.ide.AppLifecycleListener

/**
 * 引导监听器：在应用第一个窗口（欢迎页或项目窗口）显示之前实例化服务，
 * 使 AWT 即时钩子先于任务栏按钮创建就绪。
 *
 * 时机依据：appFrameCreated 由平台在 openProjectIfNeeded 决定打开哪个窗口
 * 之前同步发布（见 IdeStarter.openProjectIfNeeded，且其后续逻辑明确要求
 * 在该回调之后执行）。因此首个项目窗口 WINDOW_OPENED 时钩子已注册，
 * AUMID 可在窗口出现瞬间应用，无需等待启动完成。
 *
 * 安全性（相对已移除的 ProjectManagerListener 引导版本）：
 * - 本类构造函数无副作用；惰性监听器构造发生在订阅者表 computeIfAbsent
 *   计算中，构造期做任何平台调用都有风险；
 * - appFrameCreated 回调在订阅分发阶段执行，此时实例化服务安全；
 * - 服务构造函数不触碰消息总线（Recursive update 根因已消除）。
 */
class TaskbarUngroupBootstrap : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        // 服务构造内置 headless 守卫，此处无需额外判断
        TaskbarUngroupService.getInstance()
    }
}
