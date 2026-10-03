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

/** GWL_STYLE：取窗口基础样式（事件路径需要鉴别 WS_CHILD：EnumWindows 只给顶层窗口，WinEvent 不然） */
private const val GWL_STYLE = -16

/** WS_EX_TOOLWINDOW：工具窗口（浮窗/提示等）不在任务栏显示，跳过 */
private const val WS_EX_TOOLWINDOW = 0x00000080

/** WS_CHILD：子窗口（控件等）——绝非任务栏按钮候选 */
private const val WS_CHILD = 0x40000000

/** PROCESS_QUERY_LIMITED_INFORMATION：仅查询进程映像路径所需的最小权限 */
private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

/** EVENT_OBJECT_SHOW：对象（窗口）可见时发布；WinEvent 事件常量中最贴近任务栏按钮产生时机的一个 */
private const val EVENT_OBJECT_SHOW = 0x8002

/** OBJID_WINDOW：事件属于窗口对象本身（而非其子对象/标题栏等） */
private const val OBJID_WINDOW = 0x00000000

/** WINEVENT_OUTOFCONTEXT：回调不被映射进事件源进程，事件经安装线程的消息循环异步派发（无注入） */
private const val WINEVENT_OUTOFCONTEXT = 0x0000

/** WINEVENT_SKIPOWNPROCESS：跳过 IDE 自身进程产生的事件（项目窗口走 peer 路径） */
private const val WINEVENT_SKIPOWNPROCESS = 0x0002

interface User32 : StdCallLibrary {

    /** BOOL EnumWindows(WNDENUMPROC, LPARAM)：枚举所有顶层窗口，回调返回 false 终止 */
    fun EnumWindows(lpEnumFunc: WNDENUMPROC, lParam: Long): Boolean

    fun IsWindowVisible(hwnd: Long): Boolean

    /** 可见性过滤会漏掉无标题的真窗口吗——不会：无标题窗口本就不产生任务栏按钮 */
    fun GetWindowTextLengthW(hwnd: Long): Int

    fun GetWindowLongW(hwnd: Long, nIndex: Int): Int

    fun GetWindowThreadProcessId(hwnd: Long, lpdwProcessId: IntByReference): Int

    /**
     * HWINEVENTHOOK SetWinEventHook(eventMin, eventMax, hmodWinEventProc, pfnWinEventProc,
     * idProcess, idThread, dwFlags)：系统级窗口事件订阅。out-of-context 模式下回调
     * 由**安装线程的消息循环**派发（AWT EDT 即常驻消息泵）；返回 null 表示安装失败
     * （如系统限制），调用方降级为纯轮询。hmodWinEventProc 传 null。
     */
    fun SetWinEventHook(
        eventMin: Int,
        eventMax: Int,
        hmodWinEventProc: Pointer?,
        pfnWinEventProc: WINEVENTPROC,
        idProcess: Int,
        idThread: Int,
        dwFlags: Int,
    ): Pointer?

    /** BOOL UnhookWinEvent(HWINEVENTHOOK) */
    fun UnhookWinEvent(hWinEventHook: Pointer): Boolean

    /**
     * BOOL SetWindowPos(hWnd, hWndInsertAfter, X, Y, cx, cy, uFlags)：
     * 以全 no-op 坐标 + SWP_FRAMECHANGED 调用可促使系统重算窗口非客户区
     * （用于清空 AUMID 后推动任务栏立即重估分组）。
     */
    fun SetWindowPos(hWnd: Long, hWndInsertAfter: Long, x: Int, y: Int, cx: Int, cy: Int, uFlags: Int): Boolean

    /** Kotlin fun interface 嵌套声明以支持 SAM 转换；JNA 据此生成原生回调桩 */
    fun interface WNDENUMPROC : StdCallLibrary.StdCallCallback {
        fun invoke(hwnd: Long, lParam: Long): Boolean
    }

    /** WinEventProc(hHook, event, hwnd, idObject, idChild, idThread, dwmsEventTime) */
    fun interface WINEVENTPROC : StdCallLibrary.StdCallCallback {
        fun invoke(hHook: Pointer?, event: Int, hwnd: Long, idObject: Int, idChild: Int, idThread: Int, dwmsEventTime: Int)
    }

    companion object {
        val INSTANCE: User32 = Native.load("user32", User32::class.java)

        /** 对外只读地暴露判定常量（避免魔法数字散落调用方） */
        val WS_EX_TOOLWINDOW_MASK: Int get() = WS_EX_TOOLWINDOW
        val GWL_EXSTYLE_INDEX: Int get() = GWL_EXSTYLE
        val GWL_STYLE_INDEX: Int get() = GWL_STYLE
        val WS_CHILD_MASK: Int get() = WS_CHILD

        val EVENT_OBJECT_SHOW_EVENT: Int get() = EVENT_OBJECT_SHOW
        val OBJID_WINDOW_OBJECT: Int get() = OBJID_WINDOW
        val WINEVENT_OUTOFCONTEXT_FLAG: Int get() = WINEVENT_OUTOFCONTEXT
        val WINEVENT_SKIPOWNPROCESS_FLAG: Int get() = WINEVENT_SKIPOWNPROCESS
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
