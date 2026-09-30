package com.xixka.taskbarungroup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目打开完成后触发一次兜底应用。
 *
 * 采用 ProjectActivity（非已废弃的 StartupActivity）：
 * 调度细节（线程切换/重试）全部由 TaskbarUngroupService 处理，
 * 这里保持无状态。非 Windows 由服务内部静默守卫。
 *
 * 不在此处检查 JnaLoader.isLoaded()：若启动完成时 JNA 尚未就绪就直接
 * 返回，该项目将永久失去兜底路径（事件路径此刻早已消费完毕）。
 * 无条件调度，JNA 迟到由服务层的窗口级自愈重试覆盖。
 */
class TaskbarUngroupStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        TaskbarUngroupService.getInstance().scheduleApply(project)
    }
}
