package com.xixka.taskbarungroup

import com.intellij.jna.JnaLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.xixka.taskbarungroup.win32.Kernel32
import com.xixka.taskbarungroup.win32.User32
import com.xixka.taskbarungroup.win32.nudgeWindowFrame
import com.xixka.taskbarungroup.win32.setWindowAumid
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 外部应用窗口监视器：对用户在设置页配置的 exe（按文件名或完整路径匹配，
 * 见 [ExternalAppMatcher]），给其每个可见顶层窗口设置**每窗口唯一**的
 * AppUserModelID，使这些应用的窗口在任务栏各自独立成按钮（取消分组）。
 *
 * 与 IDE 项目窗口路径（AWT 事件 + peer 直取）不同，外部应用窗口无法收到
 * 进程内事件、也无法访问其 AWT peer，因此窗口发现采用**双通道**：
 * - 实时通道：`SetWinEventHook`（EVENT_OBJECT_SHOW，out-of-context）——
 *   系统经安装线程的消息循环派发新窗口事件，回调在 EDT 上仅做过滤与转发
 *   （微秒级返回，不阻塞消息泵），新窗口在**显示瞬间**而非下个轮询周期
 *   被处理。out-of-context 意味着不向事件源进程注入任何代码。
 * - 兜底通道：`EnumWindows` 全量枚举（2 秒周期，微秒级）——覆盖钩子安装前
 *   已存在的窗口、事件丢失（如窗口先显示后才有标题）、以及钩子不可用
 *   （安装失败自动降级为纯轮询，行为等同 0.3.x）等场景，同时承担撤销 diff。
 *
 * 进程归属：`GetWindowThreadProcessId` + `QueryFullProcessImageNameW`（最小权限），
 * 跳过 IDE 自身进程（项目窗口由事件路径按项目分组负责）。
 *
 * 撤销语义：配置移除某 exe 或列表清空后，已生效窗口清空显式 AUMID（空字符串）
 * 并触发一次 `SWP_FRAMECHANGED`（见 [nudgeWindowFrame]）促使任务栏立即重估
 * 分组；若系统仍维持旧分组，重开该应用窗口即彻底恢复（best-effort）。
 *
 * 线程模型：WinEvent 回调在 EDT（仅过滤 + 转后台）；钩子的安装/卸载也在 EDT
 * （out-of-context 钩子的事件经安装线程的消息循环派发，而 AWT EDT 恰为常驻
 * 消息泵线程）；sweep 与实时处理均在后台线程执行、由 [sweepLock] 串行化共享
 * 记账；定时任务体整体 try/catch（异常逃逸会终止 scheduleWithFixedDelay）。
 */
internal class ExternalAppWatcher {

    private val log = Logger.getInstance(ExternalAppWatcher::class.java)

    private val sweepLock = Any()

    /** JNA 未就绪提示只记一次 */
    private var jnaMissingLogged = false

    /** 诊断：sweep 轮次计数 */
    private var sweepCount = 0

    /** 诊断：上次摘要日志时的匹配窗口数（仅变化时记录，避免刷屏） */
    private var lastLoggedMatched = -1

    /** 已受理（成功或放弃）的窗口 hwnd 集合，避免重复应用 */
    private val handled = ConcurrentHashMap.newKeySet<Long>()

    /** 受理失败重试计数（放弃后条目留在 [handled] 中不再增长） */
    private val attempts = ConcurrentHashMap<Long, Int>()

    @Volatile
    private var future: ScheduledFuture<*>? = null

    /** WinEvent 钩子句柄（null = 未安装；仅 EDT 读写，读取方 volatile 可见） */
    @Volatile
    private var winEventHook: Pointer? = null

    /** 钩子安装失败（系统拒绝）后不再重试，静默保持纯轮询 */
    @Volatile
    private var winEventHookFailed = false

    /** 钩子安装请求已排队（避免 sweep 周期性向 EDT 灌 invokeLater） */
    @Volatile
    private var winEventHookPending = false

    /**
     * 钩子回调对象强引用字段：JNA 虽在原生桩表内持有回调，仍显式保活以
     * 明确生命周期与钩子一致（防 GC + 文档化意图）。
     */
    private val winEventCallback: User32.WINEVENTPROC = User32.WINEVENTPROC { _, _, hwnd, idObject, _, _, _ ->
        try {
            // 仅窗口对象本身的 SHOW（跳过控件/标题栏等子对象）；目标为空时直接返回
            if (idObject == User32.OBJID_WINDOW_OBJECT && future != null) {
                AppExecutorUtil.getAppExecutorService().execute { handleWindowShown(hwnd) }
            }
        } catch (_: Throwable) {
            // 回调位于 EDT 消息泵内，吞掉一切异常（JNA 会记录但不得打断消息循环）
        }
    }

    /** 设置列表非空时确保监视已启动（幂等）：轮询兜底 + 实时钩子。 */
    fun ensureStarted() {
        if (future != null) return
        synchronized(this) {
            if (future != null) return
            future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                ::sweepOnce,
                INITIAL_DELAY_MS,
                SWEEP_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            log.info("Taskbar Ungroup: external app watcher started (${SWEEP_INTERVAL_MS}ms sweep + WinEvent real-time hook)")
        }
        installWinEventHookAsync()
    }

    /**
     * 设置变化入口（Configurable.apply / 服务启动时调用）：
     * 列表清空 → 停止监视并尽力恢复；否则启动并立即补扫。
     */
    fun listChanged() {
        if (!SystemInfoRt.isWindows) return
        val targets = TaskbarUngroupSettings.getInstance().targetKeys()
        if (targets.isEmpty) {
            stopAndUndo()
        } else {
            ensureStarted()
            sweepNow()
        }
    }

    fun sweepNow() {
        AppExecutorUtil.getAppExecutorService().execute(::sweepOnce)
    }

    private fun stopAndUndo() {
        future?.cancel(false)
        future = null
        uninstallWinEventHook()
        // 快照+清账必须与 sweep/实时路径互斥：否则在途 sweep（持旧 targets）可在
        // 清账之后继续写 AUMID 并登记 handled，导致清空列表后残留已写窗口
        val toUndo: Array<Long>
        synchronized(sweepLock) {
            toUndo = handled.toTypedArray()
            handled.clear()
            attempts.clear()
        }
        if (toUndo.isNotEmpty() && JnaLoader.isLoaded()) {
            AppExecutorUtil.getAppExecutorService().execute {
                toUndo.forEach(::clearWindowAumid)
                log.info("Taskbar Ungroup: external app watcher stopped, ${toUndo.size} window(s) reset")
            }
        } else {
            log.info("Taskbar Ungroup: external app watcher stopped")
        }
    }

    // ---- 实时通道（WinEvent 钩子） ----------------------------------------

    private fun installWinEventHookAsync() {
        if (winEventHook != null || winEventHookFailed || winEventHookPending) return
        winEventHookPending = true
        ApplicationManager.getApplication().invokeLater {
            winEventHookPending = false
            installWinEventHook()
        }
    }

    /** EDT：注册 out-of-context 钩子；JNA 未就绪时由 sweep 周期重试，失败降级纯轮询。 */
    private fun installWinEventHook() {
        if (winEventHook != null || winEventHookFailed || !SystemInfoRt.isWindows) return
        if (!JnaLoader.isLoaded()) return
        try {
            val hook = User32.INSTANCE.SetWinEventHook(
                User32.EVENT_OBJECT_SHOW_EVENT,
                User32.EVENT_OBJECT_SHOW_EVENT,
                null,
                winEventCallback,
                0,
                0,
                User32.WINEVENT_OUTOFCONTEXT_FLAG or User32.WINEVENT_SKIPOWNPROCESS_FLAG,
            )
            if (hook != null) {
                winEventHook = hook
                log.info("Taskbar Ungroup: WinEvent hook installed (EVENT_OBJECT_SHOW, real-time path active)")
            } else {
                winEventHookFailed = true
                log.warn("Taskbar Ungroup: SetWinEventHook rejected, falling back to ${SWEEP_INTERVAL_MS}ms polling only")
            }
        } catch (t: Throwable) {
            winEventHookFailed = true
            log.warn("Taskbar Ungroup: WinEvent hook install failed, polling fallback only", t)
        }
    }

    private fun uninstallWinEventHook() {
        val hook = winEventHook ?: return
        ApplicationManager.getApplication().invokeLater {
            try {
                User32.INSTANCE.UnhookWinEvent(hook)
            } catch (t: Throwable) {
                log.warn("Taskbar Ungroup: WinEvent hook uninstall failed", t)
            }
            if (winEventHook === hook) winEventHook = null
        }
    }

    /** 实时路径处理（后台线程）：候选校验 → 匹配 → 应用，复用 sweep 的记账与重试语义。 */
    private fun handleWindowShown(hwnd: Long) {
        try {
            if (future == null || !JnaLoader.isLoaded()) return
            val settings = TaskbarUngroupSettings.getInstance()
            val targets = ExternalAppMatcher.targetsOf(settings.entryPaths(), settings.matchMode())
            if (targets.isEmpty) return
            synchronized(sweepLock) {
                applyIfMatched(hwnd, targets)
            }
        } catch (t: Throwable) {
            log.warn("Taskbar Ungroup: real-time handle failed for 0x${java.lang.Long.toHexString(hwnd)}", t)
        }
    }

    // ---- 兜底通道（周期轮询） --------------------------------------------

    private fun sweepOnce() {
        // 异常绝不能逃逸：ScheduledExecutorService 的周期任务一旦抛出即永久终止
        try {
            doSweep()
        } catch (t: Throwable) {
            log.warn("Taskbar Ungroup: external sweep failed", t)
        }
    }

    private fun doSweep() {
        if (!SystemInfoRt.isWindows) return
        if (!JnaLoader.isLoaded()) {
            if (!jnaMissingLogged) {
                jnaMissingLogged = true
                log.info("Taskbar Ungroup: JNA not loaded yet, external sweeps idle until ready")
            }
            return
        }
        // 钩子自愈：JNA 在服务启动后才就绪的场景，由 sweep 周期触发补装
        if (winEventHook == null && !winEventHookFailed && !winEventHookPending) {
            installWinEventHookAsync()
        }
        val settings = TaskbarUngroupSettings.getInstance()
        val targets = ExternalAppMatcher.targetsOf(settings.entryPaths(), settings.matchMode())
        if (targets.isEmpty) return

        synchronized(sweepLock) {
            sweepCount++
            val selfPid = Kernel32.INSTANCE.GetCurrentProcessId()
            val matched = HashMap<Long, Int>()
            var visited = 0
            var titled = 0
            var noImage = 0
            val seenExes = LinkedHashMap<String, Int>()

            User32.INSTANCE.EnumWindows(User32.WNDENUMPROC { hwnd, _ ->
                try {
                    visited++
                    val pid = candidatePid(hwnd, selfPid)
                    if (pid != null) {
                        titled++
                        val imagePath = processImageFileName(pid)
                        if (imagePath == null) {
                            noImage++
                            log.warn("Taskbar Ungroup: no image name for pid $pid (hwnd 0x${java.lang.Long.toHexString(hwnd)})")
                        } else {
                            val exeName = ExternalAppMatcher.imageBasename(imagePath)
                            if (exeName != null) {
                                seenExes.merge(exeName, 1, Int::plus)
                                if (ExternalAppMatcher.matches(imagePath, targets)) {
                                    matched[hwnd] = pid
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    log.warn("Taskbar Ungroup: enum callback failed for 0x${java.lang.Long.toHexString(hwnd)}", t)
                }
                true
            }, 0L)
            // 摘要仅在匹配数变化时记录（CI 排障足够，生产不刷屏）
            if (matched.size != lastLoggedMatched) {
                lastLoggedMatched = matched.size
                log.info(
                    "Taskbar Ungroup: sweep #$sweepCount visited=$visited titled=$titled " +
                        "noImage=$noImage matched=${matched.size} mode=${targets.mode} seenExes=${seenExes.keys}",
                )
            }

            // 撤销：已受理但不再匹配（配置移除 / 窗口换了进程）的窗口
            for (hwnd in handled) {
                if (hwnd !in matched) {
                    clearWindowAumid(hwnd)
                    handled.remove(hwnd)
                    attempts.remove(hwnd)
                }
            }

            // 应用：新出现的匹配窗口（每窗口唯一 AUMID = 取消分组）
            for ((hwnd, pid) in matched) {
                if (hwnd in handled) continue
                applyToWindow(hwnd, pid, targets)
            }
        }
    }

    // ---- 共享处理原语 -----------------------------------------------------

    /**
     * 候选窗口校验（实时/轮询共用）：可见、有标题、非工具窗口、非子窗口、
     * 归属非 IDE 进程。返回 pid，非候选返回 null。
     * （WS_CHILD 过滤对 EnumWindows 冗余——它本就只枚举顶层窗口——但对
     * WinEvent 路径必需：SHOW 事件同样来自控件/子对象所属窗口。）
     */
    private fun candidatePid(hwnd: Long, selfPid: Int): Int? {
        if (!User32.INSTANCE.IsWindowVisible(hwnd)) return null
        if (User32.INSTANCE.GetWindowTextLengthW(hwnd) <= 0) return null
        if (User32.INSTANCE.GetWindowLongW(hwnd, User32.GWL_EXSTYLE_INDEX) and
            User32.WS_EX_TOOLWINDOW_MASK != 0
        ) {
            return null
        }
        if (User32.INSTANCE.GetWindowLongW(hwnd, User32.GWL_STYLE_INDEX) and
            User32.WS_CHILD_MASK != 0
        ) {
            return null
        }
        val pidRef = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
        val pid = pidRef.value
        // 跳过 IDE 自身进程：项目窗口由事件路径负责（语义为按项目分组）
        if (pid <= 0 || pid == selfPid) return null
        return pid
    }

    /**
     * 实时路径单窗口处理：候选 + 归属 + 匹配后应用（含记账/重试/放弃语义）。
     */
    private fun applyIfMatched(hwnd: Long, targets: ExternalAppMatcher.Targets) {
        if (hwnd in handled) return
        val pid = candidatePid(hwnd, Kernel32.INSTANCE.GetCurrentProcessId()) ?: return
        val imagePath = processImageFileName(pid) ?: return
        if (!ExternalAppMatcher.matches(imagePath, targets)) return
        applyToWindow(hwnd, pid, targets)
    }

    /** 写入每窗口唯一 AUMID；失败按 [attempts] 重试，超过上限放弃（权限受限窗口等）。 */
    private fun applyToWindow(hwnd: Long, pid: Int, targets: ExternalAppMatcher.Targets) {
        // 停止守卫（volatile）：在途 sweep/实时任务可能持有停止前的旧 targets，
        // stopAndUndo 清账后不得再写入——否则清空列表的窗口会残留 AUMID。
        // 与 stopAndUndo 的清账段同持 [sweepLock]，检查-写入不可分割。
        if (future == null) return
        // merge 的可空返回是 Map 契约形式（CHM + 非空 remapping 实际不会为 null）
        val attempt = attempts.merge(hwnd, 1, Int::plus) ?: 1
        val aumid = externalAumid(pid, hwnd)
        val hr = try {
            setWindowAumid(Pointer(hwnd), aumid)
        } catch (t: Throwable) {
            log.warn("Taskbar Ungroup: apply to external window 0x${java.lang.Long.toHexString(hwnd)} failed", t)
            -1
        }
        if (hr == S_OK) {
            handled.add(hwnd)
            attempts.remove(hwnd)
            log.info("Taskbar Ungroup: external window 0x${java.lang.Long.toHexString(hwnd)} ($pid, ${targets.mode}) → '$aumid'")
        } else if (attempt >= MAX_SWEEP_ATTEMPTS) {
            // 长期失败（如权限受限窗口）放弃，避免无界重试与日志噪声
            handled.add(hwnd)
            log.warn("Taskbar Ungroup: giving up on external window 0x${java.lang.Long.toHexString(hwnd)} (hr=0x${Integer.toHexString(hr)})")
        }
    }

    /** 清空显式 AUMID 并触发帧重算（nudge），推动任务栏立即重估分组。 */
    private fun clearWindowAumid(hwnd: Long) {
        try {
            val hr = setWindowAumid(Pointer(hwnd), "")
            if (hr == S_OK) {
                nudgeWindowFrame(Pointer(hwnd))
                log.info("Taskbar Ungroup: external window 0x${java.lang.Long.toHexString(hwnd)} AUMID cleared + frame nudged")
            } else {
                log.warn("Taskbar Ungroup: clear AUMID failed for 0x${java.lang.Long.toHexString(hwnd)} (hr=0x${Integer.toHexString(hr)})")
            }
        } catch (t: Throwable) {
            log.warn("Taskbar Ungroup: clear AUMID failed for 0x${java.lang.Long.toHexString(hwnd)}", t)
        }
    }

    /** 打开最小权限进程句柄并查询映像完整路径；失败（受保护/已退出进程）返回 null。 */
    private fun processImageFileName(pid: Int): String? {
        val process = Kernel32.INSTANCE.OpenProcess(
            Kernel32.QUERY_LIMITED_INFORMATION,
            false,
            pid,
        ) ?: return null
        try {
            val buffer = Memory((Kernel32.PATH_BUFFER_CHARS * 2).toLong())
            val size = IntByReference(Kernel32.PATH_BUFFER_CHARS)
            val ok = Kernel32.INSTANCE.QueryFullProcessImageNameW(process, 0, buffer, size)
            return if (ok != 0) buffer.getWideString(0) else null
        } finally {
            Kernel32.INSTANCE.CloseHandle(process)
        }
    }

    /**
     * 每窗口唯一 AUMID（pid + hwnd 组合在会话内唯一），生命周期与窗口一致。
     * 注意：窗口图标来自窗口自身（hicon），AUMID 不影响按钮外观，仅影响分组。
     */
    private fun externalAumid(pid: Int, hwnd: Long): String =
        "TBG.X.$pid.${java.lang.Long.toHexString(hwnd)}"

    companion object {
        private const val S_OK = 0
        private const val INITIAL_DELAY_MS = 1500L
        private const val SWEEP_INTERVAL_MS = 2000L
        private const val MAX_SWEEP_ATTEMPTS = 10
    }
}
