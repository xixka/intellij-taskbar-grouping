package com.xixka.taskbarungroup

import com.intellij.jna.JnaLoader
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.concurrency.AppExecutorUtil
import com.xixka.taskbarungroup.win32.Kernel32
import com.xixka.taskbarungroup.win32.User32
import com.xixka.taskbarungroup.win32.setWindowAumid
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 外部应用窗口监视器：对用户在设置页配置的 exe（按文件名匹配），
 * 给其每个可见顶层窗口设置**每窗口唯一**的 AppUserModelID，
 * 使这些应用的窗口在任务栏各自独立成按钮（取消分组）。
 *
 * 与 IDE 项目窗口路径（事件驱动 + AWT peer 直取）不同，外部应用窗口
 * 无法收到事件、也无法访问其 AWT peer，因此：
 * - 窗口发现：EnumWindows 系统级枚举（2 秒周期，全量枚举耗时微秒级）；
 * - 进程归属：GetWindowThreadProcessId + QueryFullProcessImageNameW；
 * - COM 写入：复用 [setWindowAumid]（毫秒级，跨进程有效）。
 *
 * 撤销语义：配置移除某 exe 或列表清空后，已生效窗口会尽力清空显式 AUMID
 * （设为空字符串）恢复默认分组；若系统拒绝空值，重开该应用窗口即彻底恢复。
 *
 * 线程模型：sweep 全程在后台调度线程执行；定时任务体整体 try/catch
 * （任一异常若逃逸会终止 scheduleWithFixedDelay 的后续执行）。
 */
internal class ExternalAppWatcher {

    private val log = Logger.getInstance(ExternalAppWatcher::class.java)

    private val sweepLock = Any()

    /** 已受理（成功或放弃）的窗口 hwnd 集合，避免每轮重复应用 */
    private val handled = ConcurrentHashMap.newKeySet<Long>()

    /** 受理失败重试计数（放弃后条目留在 [handled] 中不再增长） */
    private val attempts = ConcurrentHashMap<Long, Int>()

    @Volatile
    private var future: ScheduledFuture<*>? = null

    /** 设置列表非空时确保轮询已启动（幂等）。 */
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
            log.info("Taskbar Ungroup: external app watcher started (${SWEEP_INTERVAL_MS}ms interval)")
        }
    }

    /**
     * 设置变化入口（Configurable.apply / 服务启动时调用）：
     * 列表清空 → 停止轮询并尽力恢复；否则启动并立即补扫。
     */
    fun listChanged() {
        if (!SystemInfoRt.isWindows) return
        val targets = TaskbarUngroupSettings.getInstance().exeNamesLower()
        if (targets.isEmpty()) {
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
        val toUndo = handled.toTypedArray()
        handled.clear()
        attempts.clear()
        if (toUndo.isNotEmpty() && JnaLoader.isLoaded()) {
            AppExecutorUtil.getAppExecutorService().execute {
                toUndo.forEach(::clearWindowAumid)
                log.info("Taskbar Ungroup: external app watcher stopped, ${toUndo.size} window(s) reset")
            }
        } else {
            log.info("Taskbar Ungroup: external app watcher stopped")
        }
    }

    private fun sweepOnce() {
        // 异常绝不能逃逸：ScheduledExecutorService 的周期任务一旦抛出即永久终止
        try {
            doSweep()
        } catch (t: Throwable) {
            log.warn("Taskbar Ungroup: external sweep failed", t)
        }
    }

    private fun doSweep() {
        if (!SystemInfoRt.isWindows || !JnaLoader.isLoaded()) return
        val targets = TaskbarUngroupSettings.getInstance().exeNamesLower()
        if (targets.isEmpty()) return

        synchronized(sweepLock) {
            val selfPid = Kernel32.INSTANCE.GetCurrentProcessId()
            val matched = HashMap<Long, Int>()

            User32.INSTANCE.EnumWindows(User32.WNDENUMPROC { hwnd, _ ->
                try {
                    if (User32.INSTANCE.IsWindowVisible(hwnd) &&
                        User32.INSTANCE.GetWindowTextLengthW(hwnd) > 0 &&
                        User32.INSTANCE.GetWindowLongW(hwnd, User32.GWL_EXSTYLE_INDEX) and
                            User32.WS_EX_TOOLWINDOW_MASK == 0
                    ) {
                        val pidRef = IntByReference()
                        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
                        val pid = pidRef.value
                        // 跳过 IDE 自身进程：项目窗口由事件路径负责（语义为按项目分组）
                        if (pid > 0 && pid != selfPid) {
                            val exeName = processImageFileName(pid)?.lowercase()?.substringAfterLast('\\')
                            if (exeName != null && exeName in targets) {
                                matched[hwnd] = pid
                            }
                        }
                    }
                } catch (t: Throwable) {
                    log.warn("Taskbar Ungroup: enum callback failed for 0x${java.lang.Long.toHexString(hwnd)}", t)
                }
                true
            }, 0L)

            // 撤销：已受理但不再匹配（配置移除 / 窗口换了进程 / 配置整体清空）的窗口
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
                    log.info("Taskbar Ungroup: external window 0x${java.lang.Long.toHexString(hwnd)} ($pid) → '$aumid'")
                } else if (attempt >= MAX_SWEEP_ATTEMPTS) {
                    // 长期失败（如权限受限窗口）放弃，避免无界重试与日志噪声
                    handled.add(hwnd)
                    log.warn("Taskbar Ungroup: giving up on external window 0x${java.lang.Long.toHexString(hwnd)} (hr=0x${Integer.toHexString(hr)})")
                }
            }
        }
    }

    /** 尽力恢复默认分组：清空显式 AUMID（空字符串）。失败无害，重开窗口即彻底恢复。 */
    private fun clearWindowAumid(hwnd: Long) {
        try {
            if (JnaLoader.isLoaded()) {
                setWindowAumid(Pointer(hwnd), "")
            }
        } catch (t: Throwable) {
            log.warn("Taskbar Ungroup: reset external window failed", t)
        }
    }

    /** 进程映像完整路径；失败（受保护进程等）返回 null。 */
    private fun processImageFileName(pid: Int): String? {
        val process = Kernel32.INSTANCE.OpenProcess(Kernel32.QUERY_LIMITED_INFORMATION, false, pid) ?: return null
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
