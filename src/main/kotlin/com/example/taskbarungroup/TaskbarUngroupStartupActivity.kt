package com.example.taskbarungroup

import com.intellij.jna.JnaLoader
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目打开完成后触发一次 AUMID 应用。
 *
 * 采用 ProjectActivity（非已废弃的 StartupActivity）：
 * 调度细节（线程切换/重试）全部由 TaskbarUngroupService 处理，
 * 这里保持无状态。非 Windows 或 JNA 未加载时静默返回。
 */
class TaskbarUngroupStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!JnaLoader.isLoaded()) {
            return
        }
        TaskbarUngroupService.getInstance().scheduleApply(project)
    }
}
