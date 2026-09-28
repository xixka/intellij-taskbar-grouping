package com.example.taskbarungroup.win32

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * 仅以结构体形式使用的 COM 最小绑定。
 *
 * 说明：JNA Structure 通过反射发现公共字段，Kotlin 属性必须加 @JvmField
 * 才会生成 public 字段，否则运行期会因找不到字段而失败。
 */
@Structure.FieldOrder("data1", "data2", "data3", "data4")
class GUID(
    @JvmField var data1: Int = 0,
    @JvmField var data2: Short = 0,
    @JvmField var data3: Short = 0,
) : Structure() {

    @JvmField
    var data4: ByteArray = ByteArray(8)

    constructor(d1: Long, d2: Int, d3: Int, d4: ByteArray) : this(d1.toInt(), d2.toShort(), d3.toShort()) {
        data4 = d4
    }

    companion object {
        /** IID_IPROPERTY_STORE {886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99} */
        @JvmField
        val IID_IPROPERTY_STORE = GUID(
            0x886D8EEB, 0x8CF2, 0x4446,
            byteArrayOf(
                0x8D.toByte(), 0x02, 0xCD.toByte(), 0xBA.toByte(),
                0x1D, 0xBD.toByte(), 0xCF.toByte(), 0x99.toByte(),
            ),
        )

        /** PKEY_AppUserModel_ID 的 FMTID {9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3} */
        @JvmField
        val FMTID_APPUSERMODEL_ID = GUID(
            0x9F4C2855, 0x9F79, 0x4B39,
            byteArrayOf(
                0xA8.toByte(), 0xD0.toByte(), 0xE1.toByte(), 0xD4.toByte(),
                0x2D, 0xE1.toByte(), 0xD5.toByte(), 0xF3.toByte(),
            ),
        )
    }
}

/** PROPERTYKEY { fmtid: GUID, pid: DWORD } */
@Structure.FieldOrder("fmtid", "pid")
class PROPERTYKEY(
    @JvmField var fmtid: GUID = GUID(),
    @JvmField var pid: Int = 0,
) : Structure()

/**
 * 仅覆盖 VT_LPWSTR 分支的简化 PROPVARIANT：
 * 8 字节头（vt + 3 个保留字）+ 8 字节联合（此处按 LPWSTR 指针解释）。
 */
@Structure.FieldOrder("vt", "wReserved1", "wReserved2", "wReserved3", "pwszVal")
class PROPVARIANT : Structure() {
    @JvmField var vt: Short = 0
    @JvmField var wReserved1: Short = 0
    @JvmField var wReserved2: Short = 0
    @JvmField var wReserved3: Short = 0
    @JvmField var pwszVal: Pointer? = null

    companion object {
        const val VT_LPWSTR: Short = 31
    }
}

/**
 * IPropertyStore vtable 调用辅助（不引入完整 COM 运行时）。
 * vtable 布局：IUnknown{0 QueryInterface, 1 AddRef, 2 Release} + IPropertyStore
 * {3 GetCount, 4 GetAt, 5 GetValue, 6 SetValue, 7 Commit}。
 */
internal object IPropertyStore {

    private const val IDX_RELEASE = 2
    private const val IDX_SET_VALUE = 6
    private const val IDX_COMMIT = 7

    private fun vtableFunction(store: Pointer, index: Int): Function {
        val vtable = store.getPointer(0)
        return Function.getFunction(
            vtable.getPointer(index.toLong() * Native.POINTER_SIZE),
            Function.ALT_CONVENTION,
        )
    }

    fun setValue(store: Pointer, key: PROPERTYKEY, value: PROPVARIANT): Int =
        vtableFunction(store, IDX_SET_VALUE).invokeInt(arrayOf(store, key, value))

    fun commit(store: Pointer): Int =
        vtableFunction(store, IDX_COMMIT).invokeInt(arrayOf(store))

    fun release(store: Pointer): Int =
        vtableFunction(store, IDX_RELEASE).invokeInt(arrayOf(store))
}
