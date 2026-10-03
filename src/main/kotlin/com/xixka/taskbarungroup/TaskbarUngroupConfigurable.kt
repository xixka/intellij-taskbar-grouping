package com.xixka.taskbarungroup

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import java.io.File
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

/**
 * 设置页：Settings → Tools → Taskbar Ungroup。
 *
 * 列表维护「需要取消任务栏分组的外部应用」exe 完整路径；匹配模式可选：
 * 按 exe 文件名（默认，大小写不敏感，应用升级/换盘不影响生效）或按完整路径
 * （精确匹配，消除同名 exe 误伤）。
 *
 * 防呆：条目命中 JetBrains IDE 可执行文件时给出警告（其窗口本就被本插件
 * 按项目分组；配置为外部应用会退化为每窗口独立按钮，并与其他 IDE 的
 * 项目分组语义冲突）。
 *
 * 默认行为（未配置任何条目）：IDEA 窗口按项目分组，外部应用不被触碰。
 * 对配置的应用仅通过 Windows Shell 官方窗口属性接口
 * （SHGetPropertyStoreForWindow 写 AppUserModelID）生效，不做任何进程注入。
 */
class TaskbarUngroupConfigurable : Configurable {

    private val model = DefaultListModel<String>()
    private lateinit var list: JBList<String>
    private lateinit var fullPathCheckbox: JBCheckBox
    private val ideExeWarning = JBLabel().apply { isVisible = false }

    override fun getDisplayName(): String = "Taskbar Ungroup"

    override fun createComponent(): JComponent {
        list = JBList(model)
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        list.emptyText.text = "No applications configured"

        // 模型增删即时刷新 IDE exe 警告（reset/addEntry/removeSelected 之外的所有路径）
        model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent) = updateIdeExeWarning()
            override fun intervalRemoved(e: ListDataEvent) = updateIdeExeWarning()
            override fun contentsChanged(e: ListDataEvent) = updateIdeExeWarning()
        })

        val decorator = ToolbarDecorator.createDecorator(list)
            .setAddAction { addEntry() }
            .setRemoveAction { removeSelected() }
            .disableUpDownActions()

        ideExeWarning.icon = AllIcons.General.Warning

        fullPathCheckbox = JBCheckBox("Match by full path (exact) instead of exe file name")

        val south = JPanel(BorderLayout(0, 4))
        south.add(fullPathCheckbox, BorderLayout.NORTH)
        south.add(ideExeWarning, BorderLayout.CENTER)

        val panel = JPanel(BorderLayout(0, 8))
        panel.add(
            JBLabel("Ungroup external applications on the Windows taskbar (matched by exe file name, e.g. chrome.exe)."),
            BorderLayout.NORTH,
        )
        panel.add(decorator.createPanel(), BorderLayout.CENTER)
        panel.add(south, BorderLayout.SOUTH)
        return panel
    }

    private fun addEntry() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withTitle("Choose Application")
            .withDescription("Select an application (.exe) whose windows should be ungrouped on the taskbar")
            .withFileFilter { file -> !file.isDirectory && "exe".equals(file.extension, ignoreCase = true) }
        val file = FileChooser.chooseFile(descriptor, null, null) ?: return
        val path = File(file.path).absolutePath
        if (!model.contains(path)) {
            model.addElement(path)
        }
    }

    private fun removeSelected() {
        for (path in list.selectedValuesList) {
            model.removeElement(path)
        }
    }

    /** 条目基名命中 JetBrains 产品映像 → 显示冲突警告（不阻止，仅提示） */
    private fun updateIdeExeWarning() {
        val offending = currentPaths()
            .mapNotNull { path ->
                val base = path.trim().substringAfterLast('\\').substringAfterLast('/')
                JETBRAINS_IDE_EXECUTABLES.firstOrNull { it.equals(base, ignoreCase = true) }
            }
            .distinct()
        if (offending.isEmpty()) {
            ideExeWarning.isVisible = false
            return
        }
        ideExeWarning.text = "Warning: ${offending.joinToString()} belongs to a JetBrains IDE — this plugin already groups IDE windows by project. " +
            "Configuring it here turns every IDE window into its own taskbar button and conflicts with project grouping (other installed IDEs too). " +
            "Recommended only if per-window buttons are really intended."
        ideExeWarning.isVisible = true
    }

    override fun isModified(): Boolean =
        settingsPaths() != currentPaths() ||
            TaskbarUngroupSettings.getInstance().matchFullPath != fullPathCheckbox.isSelected

    override fun apply() {
        TaskbarUngroupSettings.getInstance().apply {
            setEntryPaths(currentPaths())
            matchFullPath = fullPathCheckbox.isSelected
        }
        TaskbarUngroupService.getInstance().externalListChanged()
    }

    override fun reset() {
        model.clear()
        settingsPaths().forEach(model::addElement)
        fullPathCheckbox.isSelected = TaskbarUngroupSettings.getInstance().matchFullPath
        updateIdeExeWarning()
    }

    override fun disposeUIResources() {
        model.clear()
    }

    private fun settingsPaths(): List<String> = TaskbarUngroupSettings.getInstance().entryPaths()

    private fun currentPaths(): List<String> = model.elements().toList()

    companion object {
        /** 含任务栏窗口的 JetBrains 产品可执行文件（防呆黑名单；fsnotifier 等无窗口辅助进程不列） */
        private val JETBRAINS_IDE_EXECUTABLES = setOf(
            "idea64.exe", "idea.exe",
            "pycharm64.exe", "pycharm.exe",
            "clion64.exe", "clion.exe",
            "rider64.exe", "rider.exe",
            "goland64.exe", "goland.exe",
            "webstorm64.exe", "webstorm.exe",
            "rubymine64.exe", "rubymine.exe",
            "phpstorm64.exe", "phpstorm.exe",
            "datagrip64.exe", "datagrip.exe",
            "dataspell64.exe",
            "rustrover64.exe",
            "aqua64.exe",
            "writerside64.exe",
            "jetbrains-client64.exe",
            "jetbrains-toolbox.exe",
            "studio64.exe",
        )
    }
}
