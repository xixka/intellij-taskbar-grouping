package com.xixka.taskbarungroup

import com.intellij.jna.JnaLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.xixka.taskbarungroup.win32.componentHwnd
import com.xixka.taskbarungroup.win32.findHwndByTitle
import com.xixka.taskbarungroup.win32.setWindowAumid
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.WindowEvent
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 应用级服务：在每个项目窗口就绪后为其设置窗口级 AppUserModelID。
 *
 * 线程模型：
 * - 调度入口统一切到 EDT 读取 Frame 标题（WindowManager 访问需 EDT）；
 * - Win32 枚举与 COM 调用放入后台线程池，避免阻塞 UI；
 * - 失败重试用 AppScheduledExecutorService 延迟重入，不使用 Alarm，
 *   避免对 Disposable 父对象的生命周期依赖。
 */
@Service(Service.Level.APP)
class TaskbarUngroupService {

    private val log = Logger.getInstance(TaskbarUngroupService::class.java)

    /** 已成功应用 AUMID 的项目 -> AUMID（防止重复设置与窗口标题抖动后误重设） */
    private val applied = ConcurrentHashMap<Project, String>()

    init {
        // 注意：构造函数内不得触碰消息总线。若本服务在某个 topic 的惰性监听器
        // 构造/消息发布过程中被实例化，在总线订阅者表的 computeIfAbsent 计算
        // 中嵌套 subscribe 会触发 ConcurrentHashMap "Recursive update" 异常
        // （实测于 IDEA 2026.1.3）。Project 清理由 purgeDisposedProjects 惰性完成。

        // 即时应用钩子：项目窗口创建/激活事件直取 HWND 应用 AUMID（毫秒级），
        // 将任务栏按钮重组压缩到新窗口出现动画之内。标题匹配路径作为兜底。
        if (SystemInfoRt.isWindows) {
            Toolkit.getDefaultToolkit().addAWTEventListener(::onAwtEvent, AWTEvent.WINDOW_EVENT_MASK)
        }
    }

    /**
     * 惰性清理已关闭项目的条目：以 Project 为键持有强引用，若不清理会在长会话
     * （反复开关项目）中累积泄漏。不订阅 ProjectManager.TOPIC，避免任何
     * 总线交互带来的实例化上下文约束；在两条应用路径入口处顺带清理即可。
     */
    private fun purgeDisposedProjects() {
        if (applied.isNotEmpty()) {
            applied.keys.removeIf { it.isDisposed }
        }
    }

    /**
     * AWT 窗口事件回调（EDT）。仅处理 WINDOW_OPENED / WINDOW_ACTIVATED 且
     * 目标为携带项目的 IdeFrame（项目主窗口）。读取 HWND 后转后台线程执行 COM。
     */
    private fun onAwtEvent(event: AWTEvent) {
        if (event.id != WindowEvent.WINDOW_OPENED && event.id != WindowEvent.WINDOW_ACTIVATED) return
        val window = (event as? WindowEvent)?.window ?: return
        if (window !is IdeFrame) return
        if (!JnaLoader.isLoaded()) return
        purgeDisposedProjects()
        try {
            val project = window.project ?: return
            if (project.isDisposed || applied.containsKey(project)) return
            val aumid = buildAumid(project)
            val hwnd = componentHwnd(window)
            if (hwnd == null) {
                scheduleApply(project)
                return
            }
            AppExecutorUtil.getAppExecutorService().execute {
                if (project.isDisposed || applied.containsKey(project)) return@execute
                val hr = setWindowAumid(hwnd, aumid)
                if (hr == S_OK) {
                    applied[project] = aumid
                    log.info("Taskbar Ungroup: AUMID '$aumid' applied on window event (project '${project.name}')")
                } else {
                    log.warn("Taskbar Ungroup: instant apply failed, hr=0x${Integer.toHexString(hr)}, falling back to title matching")
                    scheduleApply(project)
                }
            }
        } catch (t: Throwable) {
            log.warn("Taskbar Ungroup: window hook failed", t)
        }
    }

    fun scheduleApply(project: Project, attempt: Int = 0) {
        if (!SystemInfoRt.isWindows || project.isDisposed) {
            return
        }
        purgeDisposedProjects()
        if (applied.containsKey(project)) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || applied.containsKey(project)) {
                return@invokeLater
            }
            val frame = WindowManager.getInstance().getFrame(project)
            val title = frame?.title
            if (frame == null || title.isNullOrBlank()) {
                // Frame 尚未创建或标题未设置：稍后重试
                retryLater(project, attempt)
            } else {
                applyAsync(project, title, attempt)
            }
        }
    }

    private fun applyAsync(project: Project, title: String, attempt: Int) {
        val aumid = buildAumid(project)
        AppExecutorUtil.getAppExecutorService().execute {
            val hwnd = findHwndByTitle(title)
            if (hwnd == null) {
                // 原生窗口可能尚未创建或标题尚未同步：稍后重试
                retryLater(project, attempt)
                return@execute
            }
            val hr = setWindowAumid(hwnd, aumid)
            if (hr == S_OK) {
                applied[project] = aumid
                log.info("Taskbar Ungroup: AUMID '$aumid' applied to window of project '${project.name}'")
            } else {
                log.warn("Taskbar Ungroup: setWindowAumid failed for '${project.name}', hr=0x${Integer.toHexString(hr)}")
                retryLater(project, attempt)
            }
        }
    }

    private fun retryLater(project: Project, attempt: Int) {
        if (attempt + 1 >= MAX_ATTEMPTS) {
            log.warn("Taskbar Ungroup: giving up on '${project.name}' after $MAX_ATTEMPTS attempts")
            return
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            scheduleApply(project, attempt + 1)
        }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * AUMID 稳定性要求：同一项目每次打开生成相同值（基于项目路径 SHA-256）。
     *
     * 格式约束（Microsoft Learn / appids）：不超过 128 字符、不含空格，
     * 形如 CompanyName.ProductName.SubProduct。产品名（如 "IntelliJ IDEA"）
     * 含空格等非法字符，需先过滤为 ASCII 字母数字/连字符。
     */
    private fun buildAumid(project: Project): String {
        val product = sanitizeAumidSegment(ApplicationNamesInfo.getInstance().productName)
        val path = project.basePath ?: project.name
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(AUMID_HASH_LENGTH)
        return "TBG.$product.$hash".take(AUMID_MAX_LENGTH)
    }

    private fun sanitizeAumidSegment(raw: String): String {
        val sanitized = buildString {
            for (c in raw) {
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-') {
                    append(c)
                }
            }
        }
        return sanitized.ifEmpty { "IDEA" }
    }

    companion object {
        private const val S_OK = 0
        private const val MAX_ATTEMPTS = 20
        private const val RETRY_DELAY_MS = 500L
        private const val AUMID_HASH_LENGTH = 32
        private const val AUMID_MAX_LENGTH = 128

        fun getInstance(): TaskbarUngroupService =
            ApplicationManager.getApplication().getService(TaskbarUngroupService::class.java)
    }
}
