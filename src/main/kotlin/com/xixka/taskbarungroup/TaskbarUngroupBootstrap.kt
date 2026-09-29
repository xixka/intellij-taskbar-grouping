package com.xixka.taskbarungroup

import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.SystemInfoRt

/**
 * 引导监听器：plugin.xml 的 `<applicationListeners>` 在插件加载期（任何项目
 * 窗口创建之前）构造本类并完成订阅，借此时机实例化应用级服务，确保 AWT
 * 窗口钩子在 IDE 启动后第一个项目窗口出现前就已就绪（含启动时自动重开的
 * 项目）。本类本身不处理任何事件。
 *
 * 非注册平台（非 Windows）不实例化服务，保持零开销。
 */
class TaskbarUngroupBootstrap : ProjectManagerListener {

    init {
        if (SystemInfoRt.isWindows) {
            TaskbarUngroupService.getInstance()
        }
    }
}
