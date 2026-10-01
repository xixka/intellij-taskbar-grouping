package com.xixka.taskbarungroup

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.list

/**
 * 应用级持久化设置（Settings → Tools → Taskbar Ungroup）：
 * 用户配置的「需要取消任务栏分组的外部应用」exe 路径列表。
 *
 * - 持久化到独立文件 taskbarUngroup.xml（非漫游：exe 路径是机器相关的）；
 * - 匹配语义：仅按 exe **文件名**（大小写不敏感）匹配进程映像，
 *   路径变化（应用升级/换盘）不影响生效；完整路径仅作展示与来源记录。
 */
@State(
    name = "TaskbarUngroup",
    storages = [Storage("taskbarUngroup.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.APP)
class TaskbarUngroupSettings : SimplePersistentStateComponent<TaskbarUngroupSettings.Persistent>(Persistent()) {

    class Persistent : BaseState() {
        var exeEntries by list<String>()
    }

    /** 配置的完整路径列表（展示顺序） */
    fun entryPaths(): List<String> = state.exeEntries

    fun setEntryPaths(paths: List<String>) {
        state.exeEntries = paths.distinct()
    }

    /** 归一化后的匹配键集合：exe 文件名小写 */
    fun exeNamesLower(): Set<String> =
        state.exeEntries.mapNotNull(::exeNameOf).toSet()

    private fun exeNameOf(path: String): String? {
        val name = path.substringAfterLast('/').substringAfterLast('\\').trim().lowercase()
        return name.ifEmpty { null }
    }

    companion object {
        fun getInstance(): TaskbarUngroupSettings =
            com.intellij.openapi.components.service<TaskbarUngroupSettings>()
    }
}
