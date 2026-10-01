package com.xixka.taskbarungroup

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import java.io.File
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * 设置页：Settings → Tools → Taskbar Ungroup。
 *
 * 列表维护「需要取消任务栏分组的外部应用」exe 完整路径；
 * 实际匹配按 exe 文件名（大小写不敏感），应用升级/换盘不影响生效。
 *
 * 默认行为（未配置任何条目）：IDEA 窗口按项目分组，外部应用不被触碰。
 * 对配置的应用仅通过 Windows Shell 官方窗口属性接口
 * （SHGetPropertyStoreForWindow 写 AppUserModelID）生效，不做任何进程注入。
 */
class TaskbarUngroupConfigurable : Configurable {

    private val model = DefaultListModel<String>()
    private lateinit var list: JBList<String>

    override fun getDisplayName(): String = "Taskbar Ungroup"

    override fun createComponent(): JComponent {
        list = JBList(model)
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        list.emptyText.text = "No applications configured"

        val decorator = ToolbarDecorator.createDecorator(list)
            .setAddAction { addEntry() }
            .setRemoveAction { removeSelected() }
            .disableUpDownActions()

        val panel = JPanel(BorderLayout(0, 8))
        panel.add(
            JBLabel("Ungroup external applications on the Windows taskbar (matched by exe file name, e.g. chrome.exe)."),
            BorderLayout.NORTH,
        )
        panel.add(decorator.createPanel(), BorderLayout.CENTER)
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

    override fun isModified(): Boolean = settingsPaths() != currentPaths()

    override fun apply() {
        TaskbarUngroupSettings.getInstance().setEntryPaths(currentPaths())
        TaskbarUngroupService.getInstance().externalListChanged()
    }

    override fun reset() {
        model.clear()
        settingsPaths().forEach(model::addElement)
    }

    override fun disposeUIResources() {
        model.clear()
    }

    private fun settingsPaths(): List<String> = TaskbarUngroupSettings.getInstance().entryPaths()

    private fun currentPaths(): List<String> = model.elements().toList()
}
