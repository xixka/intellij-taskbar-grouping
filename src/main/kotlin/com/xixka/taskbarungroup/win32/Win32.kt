package com.xixka.taskbarungroup.win32

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * 本插件用到的极小 Win32 API 子集（user32 / kernel32 / shell32）。
 *
 * 平台自带 JNA（见 `com.intellij.jna.JnaLoader`），因此这里仅声明直接依赖的函数，
 * 与 jna-platform 的区别是保持最小面、避免整包引入。
 */
interface User32 : StdCallLibrary {

    /** EnumWindows 的回调：返回 true 继续枚举，false 停止。 */
    fun interface WNDENUMPROC : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: Pointer, lParam: Pointer?): Boolean
    }

    fun EnumWindows(lpEnumFunc: WNDENUMPROC, lParam: Pointer?): Boolean

    fun GetWindowThreadProcessId(hWnd: Pointer, lpdwProcessId: IntByReference): Int

    fun IsWindowVisible(hWnd: Pointer): Boolean

    fun GetWindowTextW(hWnd: Pointer, lpString: CharArray, nMaxCount: Int): Int

    companion object {
        val INSTANCE: User32 = Native.load("user32", User32::class.java, W32APIOptions.UNICODE_OPTIONS)
    }
}

interface Kernel32 : StdCallLibrary {

    fun GetCurrentProcessId(): Int

    companion object {
        val INSTANCE: Kernel32 = Native.load("kernel32", Kernel32::class.java, W32APIOptions.UNICODE_OPTIONS)
    }
}

interface Shell32 : StdCallLibrary {

    /**
     * HRESULT SHGetPropertyStoreForWindow(HWND hwnd, REFIID riid, void **ppv)
     * 传入 IID_IPROPERTY_STORE 时返回窗口的 IPropertyStore* COM 接口指针。
     */
    fun SHGetPropertyStoreForWindow(hwnd: Pointer, riid: GUID, ppv: PointerByReference): Int

    companion object {
        val INSTANCE: Shell32 = Native.load("shell32", Shell32::class.java, W32APIOptions.UNICODE_OPTIONS)
    }
}
