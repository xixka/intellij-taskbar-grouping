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
 * - 匹配语义（[ExternalAppMatcher.MatchMode]）：默认按 exe **文件名**
 *   （大小写不敏感）匹配进程映像，路径变化（应用升级/换盘）不影响生效；
 *   可选按**完整路径**精确匹配（消除同名 exe 误伤）。旧配置无该标志时
 *   默认文件名模式（0.3.x 兼容）。
 * - 采用经典 PersistentStateComponent 模式（区别于 BaseState 委托工厂，
 *   后者的 list() 导入路径在 2024.1 编译目标下不可解析）。
 */
@State(
    name = "TaskbarUngroup",
    storages = [Storage("taskbarUngroup.xml", roamingType = RoamingType.DISABLED)],
)
@Service
class TaskbarUngroupSettings : PersistentStateComponent<TaskbarUngroupSettings.Persistent> {

    class Persistent {
        var exeEntries: MutableList<String> = mutableListOf()

        /** true = 按完整路径精确匹配；false（默认）= 按 exe 文件名匹配 */
        var matchFullPath: Boolean = false
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

    var matchFullPath: Boolean
        get() = state.matchFullPath
        set(value) {
            state.matchFullPath = value
        }

    /** 当前匹配模式 */
    fun matchMode(): ExternalAppMatcher.MatchMode =
        if (state.matchFullPath) ExternalAppMatcher.MatchMode.FULL_PATH else ExternalAppMatcher.MatchMode.FILE_NAME

    /** 归一化后的匹配快照（模式 + 键集合），监视器每轮读取 */
    fun targetKeys(): ExternalAppMatcher.Targets = ExternalAppMatcher.targetsOf(state.exeEntries, matchMode())

    companion object {
        fun getInstance(): TaskbarUngroupSettings =
            com.intellij.openapi.components.service<TaskbarUngroupSettings>()
    }
}
