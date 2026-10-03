package com.xixka.taskbarungroup

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 平台序列化格式验真（CI 冒烟预置 taskbarUngroup.xml 的依据）。
 *
 * 目的：让 IDE 自带的 XmlSerializer 自己给出 Persistent 状态的
 * 权威读写格式，并验证冒烟脚本预置的 XML 能被反序列化。
 * 任一断言失败都会在 CI 构建日志中直接可见。
 */
class TaskbarUngroupSettingsFormatTest {

    private val seededXml = """
        <application>
          <component name="TaskbarUngroup">
            <option name="exeEntries">
              <list>
                <option value="C:\Windows\System32\notepad.exe" />
              </list>
            </option>
          </component>
        </application>
    """.trimIndent()

    @Test
    fun `IDE canonical format round-trip`() {
        val populated = TaskbarUngroupSettings.Persistent().apply {
            exeEntries = mutableListOf("C:\\Windows\\System32\\notepad.exe", "C:\\tools\\wezterm.exe")
        }
        val component = Element("component").setAttribute("name", "TaskbarUngroup")
        XmlSerializer.serializeInto(populated, component)

        val canonical = JDOMUtil.writeElement(component)
        println("TASKBAR-UNGROUP-CANONICAL-XML:\n$canonical")

        val back = TaskbarUngroupSettings.Persistent()
        XmlSerializer.deserializeInto(back, component)
        assertEquals(populated.exeEntries, back.exeEntries)
    }

    @Test
    fun `CI seeded XML deserializes`() {
        // JDOMUtil.load 返回根元素（安全 SAX 配置，平台对裸 SAXBuilder 已弃用）
        val component = JDOMUtil.load(seededXml).getChild("component")
        val state = TaskbarUngroupSettings.Persistent()
        XmlSerializer.deserializeInto(state, component)
        println("TASKBAR-UNGROUP-SEEDED-PARSED: ${state.exeEntries}")
        assertFalse("预置的 taskbarUngroup.xml 应能反序列化出非空 exeEntries", state.exeEntries.isEmpty())
        assertEquals(listOf("C:\\Windows\\System32\\notepad.exe"), state.exeEntries)
        assertFalse("0.3.x 旧 XML 无 matchFullPath 标志 → 默认按文件名匹配", state.matchFullPath)
    }

    @Test
    fun `full-path flag round-trips through platform serializer`() {
        val populated = TaskbarUngroupSettings.Persistent().apply {
            exeEntries = mutableListOf("C:\\Windows\\System32\\notepad.exe")
            matchFullPath = true
        }
        val component = Element("component").setAttribute("name", "TaskbarUngroup")
        XmlSerializer.serializeInto(populated, component)
        val canonical = JDOMUtil.writeElement(component)
        println("TASKBAR-UNGROUP-CANONICAL-XML-FULLPATH:\n$canonical")

        val back = TaskbarUngroupSettings.Persistent()
        XmlSerializer.deserializeInto(back, component)
        assertEquals(populated.matchFullPath, back.matchFullPath)
        assertEquals(populated.exeEntries, back.exeEntries)
    }

    // ---- 数据迁移幂等性 -----------------------------------------------------

    @Test
    fun `serialize-deserialize-serialize is byte-stable (idempotent round-trip)`() {
        val populated = TaskbarUngroupSettings.Persistent().apply {
            exeEntries = mutableListOf("C:\\a\\x.exe", "C:\\b\\y.exe")
            matchFullPath = true
        }
        fun serialize(state: TaskbarUngroupSettings.Persistent): String {
            val component = Element("component").setAttribute("name", "TaskbarUngroup")
            XmlSerializer.serializeInto(state, component)
            return JDOMUtil.writeElement(component)
        }
        val first = serialize(populated)
        // 反序列化须能从字符串重新解析（模拟落盘→读盘）
        val loaded = TaskbarUngroupSettings.Persistent()
        XmlSerializer.deserializeInto(loaded, JDOMUtil.load(first))
        val second = serialize(loaded)
        assertEquals("序列化→反序列化→再序列化应字节稳定（幂等）", first, second)
    }

    @Test
    fun `loadState replaces state entirely (repeated loads never merge)`() {
        // PersistentStateComponent 契约：loadState 为整体替换——幂等重载不得叠加旧条目
        val settings = TaskbarUngroupSettings()
        settings.loadState(TaskbarUngroupSettings.Persistent().apply { exeEntries = mutableListOf("C:\\old.exe") })
        settings.loadState(TaskbarUngroupSettings.Persistent().apply { exeEntries = mutableListOf("C:\\new.exe") })
        assertEquals(listOf("C:\\new.exe"), settings.entryPaths())
    }

    @Test
    fun `setEntryPaths deduplicates preserving first-occurrence order`() {
        val settings = TaskbarUngroupSettings()
        settings.setEntryPaths(listOf("C:\\a\\x.exe", "C:\\b\\y.exe", "C:\\a\\x.exe"))
        assertEquals(listOf("C:\\a\\x.exe", "C:\\b\\y.exe"), settings.entryPaths())
    }

    @Test
    fun `matchMode and targetKeys track the matchFullPath flag`() {
        val settings = TaskbarUngroupSettings()
        assertEquals(ExternalAppMatcher.MatchMode.FILE_NAME, settings.matchMode())
        settings.setEntryPaths(listOf("C:\\Windows\\System32\\notepad.exe"))
        assertEquals(setOf("notepad.exe"), settings.targetKeys().keys)

        settings.matchFullPath = true
        assertEquals(ExternalAppMatcher.MatchMode.FULL_PATH, settings.matchMode())
        assertEquals(setOf("c:\\windows\\system32\\notepad.exe"), settings.targetKeys().keys)
    }
}
