package com.xixka.taskbarungroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // ---- 接口兼容（金标）：AUMID 是跨版本持久身份 ---------------------------

    /**
     * 独立重实现公式（与主实现无共享代码）：金标一旦失配，说明公式被改动——
     * 既有用户的任务栏分组身份与固定项（pin）关联会全部漂移，属破坏性变更。
     */
    private fun independentlyDerivedAumid(product: String, path: String): String {
        val sanitized = buildString {
            for (c in product) if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-') append(c)
        }.ifEmpty { "IDEA" }
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return "TBG.$sanitized.$hash".take(128)
    }

    @Test
    fun `golden formula values stable across versions (identity compatibility contract)`() {
        val cases = listOf(
            "IntelliJ IDEA" to "C:\\work\\proj",
            "PyCharm" to "/home/u/proj",
            "IntelliJ IDEA" to "", // 空路径兜底分支
        )
        for ((product, path) in cases) {
            assertEquals(
                "AUMID 公式漂移会破坏既有用户的分组身份/固定项关联",
                independentlyDerivedAumid(product, path),
                TaskbarUngroupService.projectAumid(product, path),
            )
        }
    }

    @Test
    fun `all real JetBrains product names yield pairwise-distinct aumids`() {
        // 净化段必须两两互异，否则两个产品同路径打开会并入同组（跨产品隔离不变量）
        val products = listOf(
            "IntelliJ IDEA", "PyCharm", "WebStorm", "CLion", "Rider", "GoLand",
            "RubyMine", "PhpStorm", "DataGrip", "DataSpell", "RustRover", "Aqua",
            "Android Studio", "JetBrains Client",
        )
        val aumids = products.map { TaskbarUngroupService.projectAumid(it, "C:\\same\\path") }
        assertEquals("产品净化段存在碰撞", products.size, aumids.toSet().size)
    }
}
