package com.xixka.taskbarungroup

/**
 * 外部应用匹配规则（纯函数、无平台依赖，可单测）。
 *
 * 两种语义（由设置页 [TaskbarUngroupSettings.matchMode] 决定）：
 * - [MatchMode.FILE_NAME]（默认，0.3.x 兼容）：按 exe **文件名**（大小写不敏感）
 *   匹配进程映像——配置完整路径或仅文件名均可，应用升级/换盘不影响生效；
 *   代价是同名不同路径的 exe 会同时生效（见 README 已知限制）。
 * - [MatchMode.FULL_PATH]：按**完整路径**精确匹配（大小写与分隔符方向不敏感），
 *   消除同名误伤，适合同机多版本并存（如两个不同安装路径的便携版）场景。
 */
object ExternalAppMatcher {

    enum class MatchMode { FILE_NAME, FULL_PATH }

    /** 不可变匹配快照：模式 + 归一化键集合（快照期间免锁读取） */
    class Targets(val mode: MatchMode, val keys: Set<String>) {
        val isEmpty: Boolean get() = keys.isEmpty()
    }

    fun targetsOf(entries: List<String>, mode: MatchMode): Targets = Targets(
        mode,
        when (mode) {
            MatchMode.FULL_PATH -> entries.mapNotNull(::normalizePath).toSet()
            MatchMode.FILE_NAME -> entries.mapNotNull(::imageBasename).toSet()
        },
    )

    fun matches(imagePath: String, targets: Targets): Boolean {
        if (targets.isEmpty) return false
        return when (targets.mode) {
            MatchMode.FILE_NAME -> {
                val basename = imageBasename(imagePath) ?: return false
                basename in targets.keys
            }
            MatchMode.FULL_PATH -> {
                val key = normalizePath(imagePath) ?: return false
                key in targets.keys
            }
        }
    }

    /** 归一化完整路径：去空白、统一反斜杠、小写（Windows 路径大小写不敏感） */
    private fun normalizePath(path: String): String? {
        val normalized = path.trim().replace('/', '\\').lowercase()
        return normalized.ifEmpty { null }
    }

    /** 归一化 exe 基名：映像完整路径或用户输入的任意形式均可（大小写不敏感） */
    fun imageBasename(path: String): String? {
        val name = path.trim().lowercase().substringAfterLast('\\').substringAfterLast('/')
        return name.ifEmpty { null }
    }
}
