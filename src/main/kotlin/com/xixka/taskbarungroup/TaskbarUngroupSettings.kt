package com.xixka.taskbarungroup

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 应用级持久化设置（Settings → Tools → Taskbar Ungroup）：
 * 用户配置的「需要取消任务栏分组的外部应用」exe 路径列表。
 *
 * - 持久化到独立文件 taskbarUngroup.xml（非漫游：exe 路径是机器相关的）；
 * - 匹配语义：仅按 exe **文件名**（大小写不敏感）匹配进程映像，
 *   路径变化（应用升级/换盘）不影响生效；完整路径仅作展示与来源记录。
 * - 采用经典 PersistentStateComponent 模式（区别于 BaseState 委托工厂，
 *   后者的 list() 导入路径在 2024.1 编译目标下不可解析）。
 */
@State(
    name = "TaskbarUngroup",
    storages = [Storage("taskbarUngroup.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.APP)
class TaskbarUngroupSettings : PersistentStateComponent<TaskbarUngroupSettings.Persistent> {

    class Persistent {
        var exeEntries: MutableList<String> = mutableListOf()
    }

    private var state = Persistent()

    override fun getState(): Persistent = state

    override fun loadState(state: Persistent) {
        this.state = state
    }

    /** 配置的完整路径列表（展示顺序） */
    fun entryPaths(): List<String> = state.exeEntries

    fun setEntryPaths(paths: List<String>) {
        state.exeEntries = paths.distinct().toMutableList()
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
