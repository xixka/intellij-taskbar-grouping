package com.xixka.taskbarungroup

import com.xixka.taskbarungroup.ExternalAppMatcher.MatchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
