package com.xixka.taskbarungroup.win32

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * 本插件用到的极小 Win32 API 子集（shell32 / user32 / kernel32）。
 *
 * 平台自带 JNA（见 `com.intellij.jna.JnaLoader`），因此这里仅声明直接依赖的函数，
 * 与 jna-platform 的区别是保持最小面、避免整包引入。
 *
 * IDE 自身窗口：句柄一律经 AWT peer 直取（Native.getComponentID）。
 * 外部应用窗口（用户在设置页配置的 exe）：只能系统级枚举，需 user32/kernel32。
 *
 * 所有函数均为显式 W 后缀或无 A/W 变体，不依赖 JNA 的字符集函数名映射。
 * HWND 在外部路径中以 Long（64 位指针值）传递：IntelliJ 平台 Windows 侧
 * 仅有 64 位 JBR，指针与 Java long 均为 8 字节，直接按整数值封送。
 */
interface Shell32 : StdCallLibrary {

    /**
     * HRESULT SHGetPropertyStoreForWindow(HWND hwnd, REFIID riid, void **ppv)
     * 传入 IID_IPROPERTY_STORE 时返回窗口的 IPropertyStore* COM 接口指针。
     * 对其他进程的窗口同样有效（属性存储挂接在窗口对象上）。
     */
    fun SHGetPropertyStoreForWindow(hwnd: Pointer, riid: GUID, ppv: PointerByReference): Int

    companion object {
        val INSTANCE: Shell32 = Native.load("shell32", Shell32::class.java, W32APIOptions.UNICODE_OPTIONS)
    }
}

/** GWL_EXSTYLE：取窗口扩展样式 */
private const val GWL_EXSTYLE = -20

/** WS_EX_TOOLWINDOW：工具窗口（浮窗/提示等）不在任务栏显示，跳过 */
private const val WS_EX_TOOLWINDOW = 0x00000080

/** PROCESS_QUERY_LIMITED_INFORMATION：仅查询进程映像路径所需的最小权限 */
private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

interface User32 : StdCallLibrary {

    /** BOOL EnumWindows(WNDENUMPROC, LPARAM)：枚举所有顶层窗口，回调返回 false 终止 */
    fun EnumWindows(lpEnumFunc: WNDENUMPROC, lParam: Long): Boolean

    fun IsWindowVisible(hwnd: Long): Boolean

    /** 可见性过滤会漏掉无标题的真窗口吗——不会：无标题窗口本就不产生任务栏按钮 */
    fun GetWindowTextLengthW(hwnd: Long): Int

    fun GetWindowLongW(hwnd: Long, nIndex: Int): Int

    fun GetWindowThreadProcessId(hwnd: Long, lpdwProcessId: IntByReference): Int

    /** Kotlin fun interface 嵌套声明以支持 SAM 转换；JNA 据此生成原生回调桩 */
    fun interface WNDENUMPROC : StdCallLibrary.StdCallCallback {
        fun invoke(hwnd: Long, lParam: Long): Boolean
    }

    companion object {
        val INSTANCE: User32 = Native.load("user32", User32::class.java)

        /** 对外只读地暴露判定常量（避免魔法数字散落调用方） */
        val WS_EX_TOOLWINDOW_MASK: Int get() = WS_EX_TOOLWINDOW
        val GWL_EXSTYLE_INDEX: Int get() = GWL_EXSTYLE
    }
}

interface Kernel32 : StdCallLibrary {

    fun GetCurrentProcessId(): Int

    /**
     * HANDLE OpenProcess(dwDesiredAccess, bInheritHandle, dwProcessId)
     * 返回 null 表示失败（如受保护进程）。PROCESS_QUERY_LIMITED_INFORMATION
     * 对提权进程一般仍可成功（最小信息查询）。
     */
    fun OpenProcess(dwDesiredAccess: Int, bInheritHandle: Boolean, dwProcessId: Int): Pointer?

    /** DWORD QueryFullProcessImageNameW(HANDLE, DWORD, LPWSTR, LPDWORD) — 返回非 0 成功 */
    fun QueryFullProcessImageNameW(
        hProcess: Pointer,
        dwFlags: Int,
        lpExeName: Pointer,
        lpdwSize: IntByReference,
    ): Int

    fun CloseHandle(hObject: Pointer): Boolean

    companion object {
        val INSTANCE: Kernel32 = Native.load("kernel32", Kernel32::class.java)

        /** 进程映像路径缓冲容量（字符）：覆盖最极端路径长度 */
        const val PATH_BUFFER_CHARS = 1024

        val QUERY_LIMITED_INFORMATION: Int get() = PROCESS_QUERY_LIMITED_INFORMATION
    }
}
