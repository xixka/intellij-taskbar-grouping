package com.xixka.taskbarungroup.win32

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.awt.Window

private const val S_OK = 0

/** PKEY_AppUserModel_ID 的 PID */
private const val PID_APPUSERMODEL_ID = 5

/**
 * 直接从 AWT Window 取原生顶层窗口句柄（JNA Native.getComponentID）。
 * peer 未创建（窗口尚未显示）时返回 null。仅在确认 JNA 已加载后、
 * 且在 EDT 上调用（AWT peer 访问要求）。
 * 相比标题枚举匹配：零重试、零歧义——同进程内两个同名项目的窗口标题
 * 完全相同，按标题匹配存在误绑定到别的项目窗口的风险。
 */
fun componentHwnd(window: Window): Pointer? {
    val hwnd = Native.getComponentID(window)
    return if (hwnd != 0L) Pointer(hwnd) else null
}

/**
 * 串行化所有 COM/JNA 结构调用：
 * 共享的 IID/FMTID 常量是可变 JNA Structure 实例，每次调用都会被
 * 自动写回（autoWrite/re-parent），并发使用同一实例存在数据竞争；
 * 同时也可避免跨线程并发触碰 COM 属性存储。调用本身耗时在毫秒级。
 */
private val comLock = Any()

/**
 * 为窗口设置窗口级 AppUserModelID（PKEY_AppUserModel_ID）并 Commit。
 * 返回 HRESULT（0 = S_OK）。所有 COM 调用经 [comLock] 串行化。
 */
fun setWindowAumid(hwnd: Pointer, aumid: String): Int = synchronized(comLock) {
    doSetWindowAumid(hwnd, aumid)
}

private fun doSetWindowAumid(hwnd: Pointer, aumid: String): Int {
    val ppv = PointerByReference()
    var hr = Shell32.INSTANCE.SHGetPropertyStoreForWindow(hwnd, GUID.IID_IPROPERTY_STORE, ppv)
    if (hr != S_OK) {
        return hr
    }
    val store = ppv.value ?: return -1
    try {
        // VT_LPWSTR：LPWSTR 指向的内存由 Memory 托管，GC 回收时自动释放
        val valueMemory = Memory(((aumid.length + 1) * 2).toLong())
        valueMemory.setWideString(0, aumid)

        val value = PROPVARIANT()
        value.vt = PROPVARIANT.VT_LPWSTR
        value.pwszVal = valueMemory
        value.write()

        val key = PROPERTYKEY(GUID.FMTID_APPUSERMODEL_ID, PID_APPUSERMODEL_ID)
        key.write()

        hr = IPropertyStore.setValue(store, key, value)
        if (hr == S_OK) {
            hr = IPropertyStore.commit(store)
        }
        return hr
    } finally {
        IPropertyStore.release(store)
    }
}
