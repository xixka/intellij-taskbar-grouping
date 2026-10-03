package com.xixka.taskbarungroup

import com.intellij.jna.JnaLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.xixka.taskbarungroup.win32.componentHwnd
import com.xixka.taskbarungroup.win32.setWindowAumid
import java.awt.AWTEvent
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.swing.Timer

/**
 * 应用级服务：在项目窗口就绪后为其设置窗口级 AppUserModelID。
 *
 * 分组语义：同一项目的**所有**窗口共用同一 AUMID（并成该项目的任务栏按钮）；
 * 不同项目各取独立 AUMID。因此幂等防抖以「窗口」为单位，AUMID 以「项目」为单位复用。
 *
 * 线程模型：
 * - EDT：AWT 事件、挂接轮询、scheduleApply 的 invokeLater（读取 Frame/peer）；
 * - 后台线程池：COM 属性存储写入（毫秒级），经 [ensureApplied] 内部派发。
 *
 * 窗口定位统一采用 AWT peer 直取 HWND（Native.getComponentID），
 * 不做标题枚举匹配——同进程内两个同名项目会产生相同标题，按标题匹配存在
 * 跨窗口误绑定的歧义。
 */
@Service
class TaskbarUngroupService {

    private val log = Logger.getInstance(TaskbarUngroupService::class.java)

    /** 项目 -> 其 AUMID（同项目后续窗口复用该值，保证并成同一按钮） */
    private val applied = ConcurrentHashMap<Project, String>()

    /** 已成功设置 AUMID 的窗口（幂等防抖：WINDOW_ACTIVATED 高频触发） */
    private val appliedWindows = Collections.newSetFromMap(ConcurrentHashMap<Window, Boolean>())

    /** 已在重试调度中的窗口（防止事件路径与兜底路径为同一窗口叠加重试链） */
    private val scheduledWindows = Collections.newSetFromMap(ConcurrentHashMap<Window, Boolean>())

    /**
     * 等待项目挂接的窗口集合。启动期平台「先显示窗口、后挂接项目」：
     * IdeProjectFrameAllocator 中 frame 创建/显示与 awaitProjectPreInit 之后的
     * assignFrame/setProject 并行执行，WINDOW_OPENED 时刻 IdeFrame.project 为
     * null（实证于平台源码）。对这些窗口启动 EDT 轮询，挂接后立即应用。
     */
    private val pendingWindows = Collections.newSetFromMap(ConcurrentHashMap<Window, Boolean>())

    /** 外部应用取消分组（设置页配置的 exe；默认无配置、行为不变） */
    private val externalWatcher = ExternalAppWatcher()

    init {
        // 注意：构造函数内不得触碰消息总线。若本服务在某个 topic 的惰性监听器
        // 构造/消息发布过程中被实例化，在总线订阅者表 computeIfAbsent 计算
        // 中嵌套 subscribe 会触发 ConcurrentHashMap "Recursive update" 异常
        // （实测于 IDEA 2026.1.3）。Project/窗口条目由 purge 惰性完成。
        //
        // headless 守卫：引导监听器（AppLifecycleListener.appFrameCreated）在
        // headless 环境同样触发，而该环境下 Toolkit 不可用。
        if (SystemInfoRt.isWindows && !GraphicsEnvironment.isHeadless()) {
            Toolkit.getDefaultToolkit().addAWTEventListener(::onAwtEvent, AWTEvent.WINDOW_EVENT_MASK)
        }
        // 已配置过外部应用则启动监视（JNA 未就绪时 sweep 自行跳过，就绪后生效）
        val loadedTargets = TaskbarUngroupSettings.getInstance().targetKeys()
        val stateFile = Paths.get(PathManager.getConfigPath(), "options", "taskbarUngroup.xml")
        log.info(
            "Taskbar Ungroup: settings loaded (mode=${loadedTargets.mode}, matchKeys=${loadedTargets.keys}, " +
                "entries=${TaskbarUngroupSettings.getInstance().entryPaths()}, " +
                "stateFile exists=${Files.exists(stateFile)} at $stateFile)",
        )
        if (SystemInfoRt.isWindows && !loadedTargets.isEmpty) {
            externalWatcher.ensureStarted()
        } else if (loadedTargets.isEmpty) {
            // 启动极早期（appFrameCreated 阶段）实例化的服务可能早于 store 装载外部化组件：
            // 延迟复检一次（幂等；若配置此刻可见则启动 watcher 并立即补扫）
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                try {
                    val reloaded = TaskbarUngroupSettings.getInstance().targetKeys()
                    log.info("Taskbar Ungroup: deferred settings re-check (mode=${reloaded.mode}, matchKeys=${reloaded.keys})")
                    if (SystemInfoRt.isWindows) externalWatcher.listChanged()
                } catch (t: Throwable) {
                    log.warn("Taskbar Ungroup: deferred settings re-check failed", t)
                }
            }, 20, TimeUnit.SECONDS)
        }
        log.info("Taskbar Ungroup: service initialized (AWT hook ${if (SystemInfoRt.isWindows) "registered" else "skipped (non-Windows)"})")
    }

    /** 设置页 apply 入口：外部应用列表变化 → 启动/停止监视并立即补扫 */
    fun externalListChanged() {
        externalWatcher.listChanged()
    }

    /**
     * 惰性清理：已关闭项目的 applied 条目（强引用防泄漏）、
     * 已销毁窗口的幂等/调度条目。仅在 EDT 上下文调用（onAwtEvent、invokeLater）。
     */
    private fun purge() {
        if (applied.isNotEmpty()) {
            applied.keys.removeIf { it.isDisposed }
        }
        if (appliedWindows.isNotEmpty()) {
            appliedWindows.removeIf { !it.isDisplayable }
        }
        if (scheduledWindows.isNotEmpty()) {
            scheduledWindows.removeIf { !it.isDisplayable }
        }
    }

    /**
     * AWT 窗口事件回调（EDT）。仅处理 WINDOW_OPENED / WINDOW_ACTIVATED 且
     * 目标为 IdeFrame。项目已挂接则（若该窗口尚未应用）立即应用；
     * 项目未挂接（启动期）转入挂接等待轮询。
     */
    private fun onAwtEvent(event: AWTEvent) {
        if (event.id != WindowEvent.WINDOW_OPENED && event.id != WindowEvent.WINDOW_ACTIVATED) return
        val window = (event as? WindowEvent)?.window ?: return
        if (window !is IdeFrame) return
        purge()
        try {
            val project = window.project
            if (project != null) {
                ensureApplied(window, project)
            } else {
                // 启动期窗口先显示、项目后挂接：转入挂接等待轮询
                watchProjectlessWindow(window)
            }
        } catch (t: Throwable) {
            log.warn("Taskbar Ungroup: window hook failed", t)
        }
    }

    /**
     * 等待项目挂接到窗口（EDT 轮询）。挂接通常发生在加载早期（远早于启动完成）。
     * 窗口关闭或超出轮询上限则放弃，由启动完成后的兜底路径接管。
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
                ensureApplied(window, project)
            } catch (t: Throwable) {
                log.warn("Taskbar Ungroup: pending window apply failed", t)
            }
        }
        timer.start()
    }

    /**
     * 对单个窗口应用其项目的 AUMID（EDT）。幂等：已在 [appliedWindows] 中的
     * 窗口直接返回；同一项目的其他窗口复用 [applied] 中已有 AUMID。
     * JNA 未就绪或 HWND 不可得时转入窗口级自愈重试。
     */
    private fun ensureApplied(window: Window, project: Project, attempt: Int = 0) {
        if (project.isDisposed || !window.isDisplayable || appliedWindows.contains(window)) return
        val aumid = applied[project] ?: buildAumid(project)
        if (!JnaLoader.isLoaded()) {
            // 首个窗口事件可能早于 JNA 原生库就绪：转自愈重试（覆盖 10 秒窗口）
            scheduleWindowRetry(window, project, attempt)
            return
        }
        val hwnd = componentHwnd(window)
        if (hwnd == null) {
            // peer 未创建（窗口尚未真正显示）：稍后重试
            scheduleWindowRetry(window, project, attempt)
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            if (project.isDisposed || appliedWindows.contains(window)) return@execute
            try {
                val hr = setWindowAumid(hwnd, aumid)
                if (hr == S_OK) {
                    applied[project] = aumid
                    appliedWindows.add(window)
                    log.info("Taskbar Ungroup: AUMID '$aumid' applied to a window of project '${project.name}'")
                } else {
                    log.warn("Taskbar Ungroup: apply failed (hr=0x${Integer.toHexString(hr)}), will retry")
                    scheduleWindowRetry(window, project, attempt)
                }
            } catch (t: Throwable) {
                // JNA 原生库可能在钩子触发与本任务执行之间仍未就绪：转自愈重试
                log.warn("Taskbar Ungroup: apply failed, will retry", t)
                scheduleWindowRetry(window, project, attempt)
            }
        }
    }

    /**
     * 窗口级自愈重试：延迟后回到 EDT 重新执行 [ensureApplied]。
     * [scheduledWindows] 去重避免事件路径与兜底路径叠加并行重试链；
     * 窗口关闭/项目销毁/已应用则终止。
     */
    private fun scheduleWindowRetry(window: Window, project: Project, attempt: Int) {
        if (attempt + 1 >= MAX_ATTEMPTS) {
            log.warn("Taskbar Ungroup: giving up on a window of '${project.name}' after $MAX_ATTEMPTS attempts")
            return
        }
        if (!scheduledWindows.add(window)) return
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                scheduledWindows.remove(window)
                if (project.isDisposed || !window.isDisplayable || appliedWindows.contains(window)) return@invokeLater
                try {
                    ensureApplied(window, project, attempt + 1)
                } catch (t: Throwable) {
                    log.warn("Taskbar Ungroup: retry failed", t)
                }
            }
        }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * 兜底入口（ProjectManagerListener.projectOpened 调用）。不依赖 JNA 当下是否就绪：
     * JNA 迟到由 [scheduleWindowRetry] 自愈，避免该项目永久失去兜底。
     * Frame 尚未创建时按固定节奏重试。
     */
    fun scheduleApply(project: Project, attempt: Int = 0) {
        if (!SystemInfoRt.isWindows || project.isDisposed) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }
            purge()
            val frame = WindowManager.getInstance().getFrame(project)
            if (frame == null) {
                retryFrameLookup(project, attempt)
            } else {
                ensureApplied(frame, project)
            }
        }
    }

    private fun retryFrameLookup(project: Project, attempt: Int) {
        if (attempt + 1 >= MAX_ATTEMPTS) {
            log.warn("Taskbar Ungroup: no frame found for '${project.name}' after $MAX_ATTEMPTS attempts")
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
    private fun buildAumid(project: Project): String =
        projectAumid(ApplicationNamesInfo.getInstance().productName, project.basePath ?: project.name)

    companion object {
        private const val S_OK = 0
        private const val MAX_ATTEMPTS = 20
        private const val RETRY_DELAY_MS = 500L
        private const val AUMID_HASH_LENGTH = 32
        private const val AUMID_MAX_LENGTH = 128
        private const val PENDING_POLL_MS = 150
        private const val PENDING_MAX_POLLS = 60

        /**
         * 纯函数 AUMID 构造（无平台依赖，可单测）：
         * `TBG.<净化产品段>.<项目路径 SHA-256 前 32 位>`。
         * 产品段参与 AUMID → 不同 IDE（IDEA/PyCharm）同路径打开也绝不并入同组。
         */
        internal fun projectAumid(productName: String, projectPath: String): String {
            val product = sanitizeAumidSegment(productName)
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(projectPath.toByteArray(Charsets.UTF_8))
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

        fun getInstance(): TaskbarUngroupService =
            ApplicationManager.getApplication().getService(TaskbarUngroupService::class.java)
    }
}
