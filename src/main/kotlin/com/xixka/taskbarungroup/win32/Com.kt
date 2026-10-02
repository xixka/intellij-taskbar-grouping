package com.xixka.taskbarungroup.win32

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

        /** PKEY_AppUserModel_ID 的 FMTID（官方：propkey.h / MSDN）
         *  {9F4C2855-EE22-4C80-9C1A-1D6CE9F6D1CE}，PID=5。
         *  注意：此前误用了伪造 GUID，写属性虽返回 S_OK 但任务栏从不读取该键，
         *  实际取消分组从未生效（CI 冒烟实证：官方键读取恒为空）。 */
        @JvmField
        val FMTID_APPUSERMODEL_ID = GUID(
            0x9F4C2855, 0xEE22, 0x4C80,
            byteArrayOf(
                0x9C.toByte(), 0x1A.toByte(), 0x1D.toByte(), 0x6C.toByte(),
                0xE9.toByte(), 0xF6.toByte(), 0xD1.toByte(), 0xCE.toByte(),
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
 * PROPVARIANT（仅按 VT_LPWSTR 使用）。
 *
 * 尺寸说明：x64 下 sizeof(PROPVARIANT) = 24 —— 8 字节头（vt + 3 个保留 WORD）
 * 加 16 字节联合（联合内最大成员为计数数组 CAUB/BLOB = ULONG + 指针）。
 * 原生实现（如 PropVariantCopy）会按完整 24 字节读取，若结构欠尺寸会造成
 * JNA 分配内存之后的越界读（未定义行为）。[unionPad] 仅用于把结构补齐到
 * 正确尺寸，对本插件使用的 VT_LPWSTR 分支无语义影响。
 */
@Structure.FieldOrder("vt", "wReserved1", "wReserved2", "wReserved3", "pwszVal", "unionPad")
class PROPVARIANT : Structure() {
    @JvmField var vt: Short = 0
    @JvmField var wReserved1: Short = 0
    @JvmField var wReserved2: Short = 0
    @JvmField var wReserved3: Short = 0
    @JvmField var pwszVal: Pointer? = null

    /** 联合体尾部的占位指针，使结构达到 x64 下 24 字节的真实尺寸。 */
    @JvmField var unionPad: Pointer? = null

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
