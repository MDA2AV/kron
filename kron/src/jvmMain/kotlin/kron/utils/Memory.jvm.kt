package kron

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.VarHandle

// One segment over the whole address space: every address the port holds is an offset into it. The JIT turns these
// accesses into plain loads and stores.
@JvmField
internal val MEM: MemorySegment = MemorySegment.NULL.reinterpret(Long.MAX_VALUE)

private val I8: ValueLayout.OfByte = ValueLayout.JAVA_BYTE
private val I16: ValueLayout.OfShort = ValueLayout.JAVA_SHORT_UNALIGNED
private val I32: ValueLayout.OfInt = ValueLayout.JAVA_INT_UNALIGNED
private val I64: ValueLayout.OfLong = ValueLayout.JAVA_LONG_UNALIGNED

internal actual fun getByte(addr: Long): Byte = MEM.get(I8, addr)

internal actual fun putByte(addr: Long, v: Byte) = MEM.set(I8, addr, v)

internal actual fun getShort(addr: Long): Short = MEM.get(I16, addr)

internal actual fun putShort(addr: Long, v: Short) = MEM.set(I16, addr, v)

internal actual fun getInt(addr: Long): Int = MEM.get(I32, addr)

internal actual fun putInt(addr: Long, v: Int) = MEM.set(I32, addr, v)

internal actual fun getLong(addr: Long): Long = MEM.get(I64, addr)

internal actual fun putLong(addr: Long, v: Long) = MEM.set(I64, addr, v)

// A plain access fenced as the kernel's side of the ring index is; on x86 both fences only stop reordering.
internal actual fun getIntAcquire(addr: Long): Int {
    val v = MEM.get(I32, addr)
    VarHandle.acquireFence()
    return v
}

internal actual fun putIntRelease(addr: Long, v: Int) {
    VarHandle.releaseFence()
    MEM.set(I32, addr, v)
}

internal actual fun putShortRelease(addr: Long, v: Short) {
    VarHandle.releaseFence()
    MEM.set(I16, addr, v)
}

internal actual fun copyMemory(src: Long, dst: Long, length: Long) = MemorySegment.copy(MEM, src, MEM, dst, length)

internal actual fun copyFromArray(src: ByteArray, srcOffset: Int, dst: Long, length: Int) =
    MemorySegment.copy(src, srcOffset, MEM, I8, dst, length)

internal actual fun copyToArray(src: Long, dst: ByteArray, dstOffset: Int, length: Int) =
    MemorySegment.copy(MEM, I8, src, dst, dstOffset, length)

internal actual fun zeroMemory(addr: Long, length: Long) {
    MEM.asSlice(addr, length).fill(0)
}
