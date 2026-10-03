package com.xixka.taskbarungroup

import com.xixka.taskbarungroup.ExternalAppMatcher.MatchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部应用匹配语义测试（文件名 / 完整路径两种模式）。
 *
 * FILE_NAME（默认）：配置完整路径或仅文件名均可命中同名映像（0.3.x 兼容）；
 * FULL_PATH：精确到安装路径，同名不同路径不误伤。
 */
class ExternalAppMatcherTest {

    private val notepadImage = "C:\\Windows\\System32\\Notepad.EXE"

    @Test
    fun `file-name mode matches by basename case-insensitively`() {
        val targets = ExternalAppMatcher.targetsOf(listOf("notepad.exe"), MatchMode.FILE_NAME)
        assertTrue(ExternalAppMatcher.matches(notepadImage, targets))
    }

    @Test
    fun `file-name mode accepts a full path entry and still matches any same-basename image`() {
        val targets = ExternalAppMatcher.targetsOf(listOf("C:\\SomeOtherDir\\notepad.exe"), MatchMode.FILE_NAME)
        assertTrue(ExternalAppMatcher.matches("D:\\tools\\NOTEPAD.exe", targets))
    }

    @Test
    fun `file-name mode does not match other names`() {
        val targets = ExternalAppMatcher.targetsOf(listOf("notepad.exe"), MatchMode.FILE_NAME)
        assertFalse(ExternalAppMatcher.matches("C:\\Windows\\System32\\mspaint.exe", targets))
    }

    @Test
    fun `full-path mode matches exact path case-and-separator-insensitively`() {
        val targets = ExternalAppMatcher.targetsOf(listOf("C:/Windows/System32/notepad.exe"), MatchMode.FULL_PATH)
        assertTrue(ExternalAppMatcher.matches(notepadImage, targets)) // 反斜杠/大写均归一
    }

    @Test
    fun `full-path mode does not match same basename at different install path`() {
        val targets = ExternalAppMatcher.targetsOf(listOf("C:\\Windows\\System32\\notepad.exe"), MatchMode.FULL_PATH)
        assertFalse(ExternalAppMatcher.matches("C:\\Portable\\notepad.exe", targets))
    }

    @Test
    fun `full-path mode does not match a bare file-name entry`() {
        val targets = ExternalAppMatcher.targetsOf(listOf("notepad.exe"), MatchMode.FULL_PATH)
        assertFalse(ExternalAppMatcher.matches(notepadImage, targets))
    }

    @Test
    fun `blank entries are filtered out and empty targets never match`() {
        val targets = ExternalAppMatcher.targetsOf(listOf("", "   "), MatchMode.FILE_NAME)
        assertTrue(targets.isEmpty)
        assertFalse(ExternalAppMatcher.matches(notepadImage, targets))
    }

    @Test
    fun `mixed entries keep only valid ones`() {
        val targets = ExternalAppMatcher.targetsOf(listOf("", "notepad.exe", "mspaint.exe"), MatchMode.FILE_NAME)
        assertEquals(setOf("notepad.exe", "mspaint.exe"), targets.keys)
    }

    // ---- 边界与畸形输入（输入解析防线的回归锚点） ---------------------------

    @Test
    fun `path ending with separator yields no basename (no false match)`() {
        // 畸形：以分隔符结尾——substringAfterLast 得空串，必须归 null 而非匹配一切
        assertNull(ExternalAppMatcher.imageBasename("C:\\Windows\\System32\\"))
        assertNull(ExternalAppMatcher.imageBasename("C:/dir/"))
        val targets = ExternalAppMatcher.targetsOf(listOf("C:\\", "\\", "/"), MatchMode.FILE_NAME)
        assertTrue("纯分隔符条目必须被过滤", targets.isEmpty)
    }

    @Test
    fun `separator-only or whitespace entries are filtered in both modes`() {
        assertTrue(ExternalAppMatcher.targetsOf(listOf("\\", "/", "  ", ""), MatchMode.FULL_PATH).isEmpty)
        assertTrue(ExternalAppMatcher.targetsOf(listOf("\\", "/", "  ", ""), MatchMode.FILE_NAME).isEmpty)
    }

    @Test
    fun `full-path mode with trailing separator does not match clean path`() {
        // 畸形条目：多一个尾分隔符 ≠ 同一路径（精确模式不静默容错）
        val targets = ExternalAppMatcher.targetsOf(listOf("C:\\App\\app.exe\\"), MatchMode.FULL_PATH)
        assertFalse(ExternalAppMatcher.matches("C:\\App\\app.exe", targets))
    }

    @Test
    fun `unicode exe names round-trip in both modes`() {
        val fullTargets = ExternalAppMatcher.targetsOf(listOf("C:\\应用\\记事本.exe"), MatchMode.FULL_PATH)
        assertTrue(ExternalAppMatcher.matches("C:\\应用\\记事本.exe", fullTargets))
        val nameTargets = ExternalAppMatcher.targetsOf(listOf("记事本.exe"), MatchMode.FILE_NAME)
        assertTrue(ExternalAppMatcher.matches("C:\\其他目录\\记事本.exe", nameTargets))
    }

    @Test
    fun `extension-less names match by exact basename`() {
        val targets = ExternalAppMatcher.targetsOf(listOf("code"), MatchMode.FILE_NAME)
        assertTrue(ExternalAppMatcher.matches("C:\\bin\\code", targets))
        assertFalse("前缀相同但更长的不算同名", ExternalAppMatcher.matches("C:\\bin\\codex", targets))
    }

    @Test
    fun `very long paths match without truncation`() {
        val longDir = "very-long-directory-name\\".repeat(80)
        val entry = "C:\\$longDir\\app.exe"
        val targets = ExternalAppMatcher.targetsOf(listOf(entry), MatchMode.FULL_PATH)
        // 归一化（大小写）后仍应整体匹配，不做截断比较
        assertTrue(ExternalAppMatcher.matches(entry.replace("very-long", "VERY-LONG"), targets))
    }
}
