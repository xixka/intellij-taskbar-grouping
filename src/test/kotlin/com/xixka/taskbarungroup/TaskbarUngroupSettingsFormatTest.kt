package com.xixka.taskbarungroup

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.jdom.input.SAXBuilder
import org.jdom.output.Format
import org.jdom.output.XMLOutputter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.StringReader

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
              <option value="C:\Windows\System32\notepad.exe" />
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

        val canonical = XMLOutputter(Format.getPrettyFormat()).outputString(component)
        println("TASKBAR-UNGROUP-CANONICAL-XML:\n$canonical")

        val back = TaskbarUngroupSettings.Persistent()
        XmlSerializer.deserializeInto(back, component)
        assertEquals(populated.exeEntries, back.exeEntries)
    }

    @Test
    fun `CI seeded XML deserializes`() {
        val doc = SAXBuilder().build(StringReader(seededXml))
        val component = doc.rootElement.getChild("component")
        val state = TaskbarUngroupSettings.Persistent()
        XmlSerializer.deserializeInto(state, component)
        println("TASKBAR-UNGROUP-SEEDED-PARSED: ${state.exeEntries}")
        assertFalse("预置的 taskbarUngroup.xml 应能反序列化出非空 exeEntries", state.exeEntries.isEmpty())
        assertEquals(listOf("C:\\Windows\\System32\\notepad.exe"), state.exeEntries)
    }
}
