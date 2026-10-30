package kron.glyph11

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

// A little-endian Long view of a byte array, which the JIT turns into one load.
private val LONGS: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

internal actual fun wordAt(a: ByteArray, at: Int): Long = LONGS.get(a, at) as Long
