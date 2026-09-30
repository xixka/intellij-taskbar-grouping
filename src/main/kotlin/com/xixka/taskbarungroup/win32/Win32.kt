package com.xixka.taskbarungroup.win32

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * 本插件用到的极小 Win32 API 子集（仅 shell32）。
 *
 * 平台自带 JNA（见 `com.intellij.jna.JnaLoader`），因此这里仅声明直接依赖的函数，
 * 与 jna-platform 的区别是保持最小面、避免整包引入。
 *
 * 窗口句柄一律经 AWT peer 直取（Native.getComponentID），不做用户态枚举，
 * 因此无需 user32/kernel32 声明。
 */
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
