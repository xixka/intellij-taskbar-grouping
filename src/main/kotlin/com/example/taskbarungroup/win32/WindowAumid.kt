package com.example.taskbarungroup.win32

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

private const val S_OK = 0

/** PKEY_AppUserModel_ID 的 PID */
private const val PID_APPUSERMODEL_ID = 5

/**
 * 按标题在当前进程内查找可见顶层窗口的 HWND。
 * 匹配规则：属于当前进程 + 可见 + GetWindowText 与预期完全一致。
 */
fun findHwndByTitle(expectedTitle: String): Pointer? {
    val pid = Kernel32.INSTANCE.GetCurrentProcessId()
    var found: Pointer? = null
    User32.INSTANCE.EnumWindows(User32.WNDENUMPROC { hwnd, _ ->
        val matches = isOwnVisibleWindowWithTitle(hwnd, pid, expectedTitle)
        if (matches) {
            found = hwnd
        }
        !matches
    }, null)
    return found
}

private fun isOwnVisibleWindowWithTitle(hwnd: Pointer, pid: Int, title: String): Boolean {
    val windowPid = IntByReference()
    User32.INSTANCE.GetWindowThreadProcessId(hwnd, windowPid)
    if (windowPid.value != pid || !User32.INSTANCE.IsWindowVisible(hwnd)) {
        return false
    }
    val buffer = CharArray(512)
    val length = User32.INSTANCE.GetWindowTextW(hwnd, buffer, buffer.size)
    if (length <= 0) {
        return false
    }
    return String(buffer, 0, length) == title
}

/**
 * 为窗口设置窗口级 AppUserModelID（PKEY_AppUserModel_ID）并 Commit。
 * 返回 HRESULT（0 = S_OK）。
 */
fun setWindowAumid(hwnd: Pointer, aumid: String): Int {
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
