@file:Suppress("NOTHING_TO_INLINE")

package kron

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.interpretPointed
import kotlinx.cinterop.nativeNullPtr
import kotlinx.cinterop.plus
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kron.sys.kr_memmove
import kron.sys.kr_memset

/*
 * Native memory by address. A `Long` works as a pointer the way `byte*` does in the C# core: the kernel-shared
 * rings, provided buffers and write slabs are all read and written through these, each a plain load or store.
 */

internal actual inline fun getByte(addr: Long): Byte = interpretPointed<ByteVar>(nativeNullPtr + addr).value
internal actual inline fun putByte(addr: Long, v: Byte) {
    interpretPointed<ByteVar>(nativeNullPtr + addr).value = v
}
internal actual inline fun getShort(addr: Long): Short = interpretPointed<ShortVar>(nativeNullPtr + addr).value
internal actual inline fun putShort(addr: Long, v: Short) {
    interpretPointed<ShortVar>(nativeNullPtr + addr).value = v
}
internal actual inline fun getInt(addr: Long): Int = interpretPointed<IntVar>(nativeNullPtr + addr).value
internal actual inline fun putInt(addr: Long, v: Int) {
    interpretPointed<IntVar>(nativeNullPtr + addr).value = v
}
internal actual inline fun getLong(addr: Long): Long = interpretPointed<LongVar>(nativeNullPtr + addr).value
internal actual inline fun putLong(addr: Long, v: Long) {
    interpretPointed<LongVar>(nativeNullPtr + addr).value = v
}

// The kernel's side of each ring index is smp_load_acquire / smp_store_release. Ours are plain: with
// SINGLE_ISSUER | DEFER_TASKRUN and no SQPOLL the kernel reads and writes the rings only inside this thread's
// own io_uring_enter, and that call is opaque to the compiler, so nothing moves across it. The weaker ordering of
// arm64 does not change that: a CPU sees its own stores in order, and a migration in between is a full barrier.
internal actual inline fun getIntAcquire(addr: Long): Int = getInt(addr)
internal actual inline fun putIntRelease(addr: Long, v: Int) = putInt(addr, v)
internal actual inline fun putShortRelease(addr: Long, v: Short) = putShort(addr, v)

// Below this a copy stays in Kotlin: every call into C switches the thread's GC state, which costs more than
// moving a few words.
private const val C_COPY_MIN = 256

/** Copies [length] bytes between native addresses; the ranges may overlap. */
internal actual fun copyMemory(src: Long, dst: Long, length: Long) {
    if (length >= C_COPY_MIN || (dst > src && dst < src + length)) {
        if (length > 0) {
            kr_memmove(dst, src, length)
        }
        return
    }

    var i = 0L
    while (i + 8 <= length) {
        putLong(dst + i, getLong(src + i))
        i += 8
    }

    while (i < length) {
        putByte(dst + i, getByte(src + i))
        i++
    }
}

/** Copies from a heap array into native memory. */
internal actual fun copyFromArray(src: ByteArray, srcOffset: Int, dst: Long, length: Int) {
    if (length >= C_COPY_MIN) {
        src.usePinned {
            kr_memmove(dst, it.addressOf(srcOffset).rawValue.toLong(), length.toLong())
        }
        return
    }

    var i = 0
    while (i + 8 <= length) {
        putLong(dst + i, src.getLongAt(srcOffset + i))
        i += 8
    }

    while (i < length) {
        putByte(dst + i, src[srcOffset + i])
        i++
    }
}

/** Copies from native memory into a heap array. */
internal actual fun copyToArray(src: Long, dst: ByteArray, dstOffset: Int, length: Int) {
    if (length >= C_COPY_MIN) {
        dst.usePinned {
            kr_memmove(it.addressOf(dstOffset).rawValue.toLong(), src, length.toLong())
        }
        return
    }

    var i = 0
    while (i + 8 <= length) {
        dst.setLongAt(dstOffset + i, getLong(src + i))
        i += 8
    }

    while (i < length) {
        dst[dstOffset + i] = getByte(src + i)
        i++
    }
}

internal actual fun zeroMemory(addr: Long, length: Long) {
    if (length >= C_COPY_MIN) {
        kr_memset(addr, 0, length)
        return
    }

    var i = 0L
    while (i + 8 <= length) {
        putLong(addr + i, 0L)
        i += 8
    }

    while (i < length) {
        putByte(addr + i, 0)
        i++
    }
}

