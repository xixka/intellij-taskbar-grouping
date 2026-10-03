package com.xixka.taskbarungroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUMID 构造纯函数（TaskbarUngroupService.projectAumid）的格式与语义测试。
 *
 * 关键不变量：
 * - 稳定性：同一项目每次打开生成相同 AUMID（任务栏分组身份不漂移）；
 * - 产品隔离：不同 IDE 打开同一路径也绝不并入同组（双开 IDEA + PyCharm 场景）；
 * - 格式约束（Microsoft 对 AppUserModelID 的要求）：≤128 字符、无空格、
 *   ASCII 字母数字/点/连字符。
 */
class ProjectAumidTest {

    private val ideaProduct = "IntelliJ IDEA"

    @Test
    fun `same input yields identical aumid (stable identity)`() {
        assertEquals(
            TaskbarUngroupService.projectAumid(ideaProduct, "C:/work/project-a"),
            TaskbarUngroupService.projectAumid(ideaProduct, "C:/work/project-a"),
        )
    }

    @Test
    fun `different products or paths yield distinct aumids (no cross-grouping)`() {
        val idea = TaskbarUngroupService.projectAumid(ideaProduct, "C:/p")
        val pycharm = TaskbarUngroupService.projectAumid("PyCharm", "C:/p") // 同一路径双开
        val otherProject = TaskbarUngroupService.projectAumid(ideaProduct, "C:/q")
        assertNotEquals(idea, pycharm)
        assertNotEquals(idea, otherProject)
    }

    @Test
    fun `product segment is ascii-sanitized`() {
        val aumid = TaskbarUngroupService.projectAumid(ideaProduct, "C:/p")
        // "IntelliJ IDEA" 的空格被剔除，不含非法字符
        assertTrue(aumid.startsWith("TBG.IntelliJIDEA."))
        assertFalse(aumid.contains(' '))
    }

    @Test
    fun `non-ascii or empty product falls back to IDEA segment`() {
        assertTrue(TaskbarUngroupService.projectAumid("中文产品名", "C:/p").startsWith("TBG.IDEA."))
        assertTrue(TaskbarUngroupService.projectAumid("", "C:/p").startsWith("TBG.IDEA."))
        // 回退段与合法产品段仍隔离（hash 一定不同）
        assertNotEquals(
            TaskbarUngroupService.projectAumid("", "C:/p"),
            TaskbarUngroupService.projectAumid("IntelliJ IDEA", "C:/p"),
        )
    }

    @Test
    fun `format constraints hold for hostile inputs`() {
        val hostile = listOf(
            "C:/normal",
            "", // 空路径（理论上 project.basePath ?: name 兜底，仍须不崩）
            "D:\\路径含中文与空格的项目\\".repeat(40), // 超长非 ASCII
        )
        for (path in hostile) {
            val aumid = TaskbarUngroupService.projectAumid(ideaProduct, path)
            assertTrue("length ≤ 128: ${aumid.length}", aumid.length <= 128)
            assertFalse("no whitespace: '$aumid'", aumid.contains(' '))
            assertTrue("charset: '$aumid'", aumid.matches(Regex("[A-Za-z0-9.\\-]+")))
            assertTrue("prefix: '$aumid'", aumid.startsWith("TBG."))
        }
    }

    @Test
    fun `hash segment is 32 lowercase hex chars`() {
        val aumid = TaskbarUngroupService.projectAumid(ideaProduct, "C:/p")
        val hash = aumid.substringAfterLast('.')
        assertEquals(32, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{32}")))
    }
}
