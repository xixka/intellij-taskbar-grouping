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
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowEvent
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.swing.Timer

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

    /**
     * 等待项目挂接的窗口集合。启动期平台「先显示窗口、后挂接项目」：
     * IdeProjectFrameAllocator 中 frame 创建/显示与 awaitProjectPreInit 之后的
     * assignFrame/setProject 并行执行，WINDOW_OPENED 时刻 IdeFrame.project 为
     * null（实证于平台源码）。对这些窗口启动 EDT 轮询，挂接后立即应用。
     */
    private val pendingWindows = Collections.newSetFromMap(ConcurrentHashMap<Window, Boolean>())

    init {
        // 注意：构造函数内不得触碰消息总线。若本服务在某个 topic 的惰性监听器
        // 构造/消息发布过程中被实例化，在总线订阅者表的 computeIfAbsent 计算
        // 中嵌套 subscribe 会触发 ConcurrentHashMap "Recursive update" 异常
        // （实测于 IDEA 2026.1.3）。Project 清理由 purgeDisposedProjects 惰性完成。

        // 即时应用钩子：项目窗口创建/激活事件直取 HWND 应用 AUMID（毫秒级），
        // 将任务栏按钮重组压缩到新窗口出现动画之内。标题匹配路径作为兜底。
        // headless 守卫：引导监听器（AppLifecycleListener.appFrameCreated）在
        // headless 环境同样触发，而该环境下 Toolkit 不可用。
        if (SystemInfoRt.isWindows && !GraphicsEnvironment.isHeadless()) {
            Toolkit.getDefaultToolkit().addAWTEventListener(::onAwtEvent, AWTEvent.WINDOW_EVENT_MASK)
            // 不主动调用 JnaLoader.load：其签名跨版本不兼容（241 为 load(Logger)，
            // 2026.x 为无参 load()），直接调用必在其中一端编译/二进制不兼容。
            // JNA 若尚未就绪，由各应用路径的 Throwable 自愈重试兜底（500ms 粒度）。
        }
        log.info("Taskbar Ungroup: service initialized (AWT hook ${if (SystemInfoRt.isWindows) "registered" else "skipped (non-Windows)"})")
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
        purgeDisposedProjects()
        try {
            val project = window.project
            if (project != null) {
                if (project.isDisposed || applied.containsKey(project)) return
                applyInstant(window, project)
            } else {
                // 启动期窗口先显示、项目后挂接：转入挂接等待轮询
                watchProjectlessWindow(window)
            }
        } catch (t: Throwable) {
            log.warn("Taskbar Ungroup: window hook failed", t)
        }
    }

    /**
     * 等待项目挂接到窗口（EDT 轮询）。平台在启动期并行执行「frame 创建/显示」与
     * 「awaitProjectPreInit 后的 assignFrame/setProject」，首窗 WINDOW_OPENED 时
     * project 为 null；挂接通常发生在加载早期（远早于启动完成）。窗口关闭或
     * 超出轮询上限则放弃，由启动完成后的兜底路径接管。
     */
    private fun watchProjectlessWindow(window: Window) {
        if (!pendingWindows.add(window)) return
        val timer = Timer(PENDING_POLL_MS, null)
        var polls = 0
        timer.addActionListener {
            polls++
            if (!window.isDisplayable || polls >= PENDING_MAX_POLLS) {
                timer.stop()
                pendingWindows.remove(window)
                return@addActionListener
            }
            val project = (window as? IdeFrame)?.project ?: return@addActionListener
            timer.stop()
            pendingWindows.remove(window)
            try {
                if (!project.isDisposed && !applied.containsKey(project)) {
                    applyInstant(window, project)
                }
            } catch (t: Throwable) {
                log.warn("Taskbar Ungroup: pending window apply failed", t)
            }
        }
        timer.start()
    }

    /** 即时路径：窗口事件（或挂接轮询）直取 HWND → 后台线程 COM 应用 */
    private fun applyInstant(window: Window, project: Project) {
        if (!JnaLoader.isLoaded()) {
            // 首个窗口事件可能早于 JNA 原生库就绪（后台预载进行中）：
            // 转入标题重试路径——其重试覆盖 10 秒窗口，JNA 就绪后即可完成
            scheduleApply(project)
            return
        }
        val aumid = buildAumid(project)
        val hwnd = componentHwnd(window)
        if (hwnd == null) {
            scheduleApply(project)
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            if (project.isDisposed || applied.containsKey(project)) return@execute
            try {
                val hr = setWindowAumid(hwnd, aumid)
                if (hr == S_OK) {
                    applied[project] = aumid
                    log.info("Taskbar Ungroup: AUMID '$aumid' applied on window event (project '${project.name}')")
                } else {
                    log.warn("Taskbar Ungroup: instant apply failed, hr=0x${Integer.toHexString(hr)}, falling back to title matching")
                    scheduleApply(project)
                }
            } catch (t: Throwable) {
                // JNA 原生库可能在钩子触发与本任务执行之间仍未就绪：转自愈重试
                log.warn("Taskbar Ungroup: instant apply failed, will retry", t)
                scheduleApply(project)
            }
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
            try {
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
            } catch (t: Throwable) {
                // 自愈式重试：覆盖 JNA 原生库尚未就绪（UnsatisfiedLinkError）等
                // 瞬态错误——首个项目窗口可能早于平台加载 JNA
                log.warn("Taskbar Ungroup: title path failed (attempt ${attempt + 1}), will retry", t)
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
        private const val PENDING_POLL_MS = 150
        private const val PENDING_MAX_POLLS = 60

        fun getInstance(): TaskbarUngroupService =
            ApplicationManager.getApplication().getService(TaskbarUngroupService::class.java)
    }
}
