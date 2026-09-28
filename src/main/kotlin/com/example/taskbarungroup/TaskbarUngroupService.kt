package com.example.taskbarungroup

import com.example.taskbarungroup.win32.findHwndByTitle
import com.example.taskbarungroup.win32.setWindowAumid
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
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

    fun scheduleApply(project: Project, attempt: Int = 0) {
        if (!SystemInfoRt.isWindows || project.isDisposed || applied.containsKey(project)) {
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
     */
    private fun buildAumid(project: Project): String {
        val product = ApplicationNamesInfo.getInstance().productName
        val path = project.basePath ?: project.name
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(AUMID_HASH_LENGTH)
        return "TBG.$product.$hash"
    }

    companion object {
        private const val S_OK = 0
        private const val MAX_ATTEMPTS = 20
        private const val RETRY_DELAY_MS = 500L
        private const val AUMID_HASH_LENGTH = 32

        fun getInstance(): TaskbarUngroupService =
            ApplicationManager.getApplication().getService(TaskbarUngroupService::class.java)
    }
}
