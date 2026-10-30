package kron.glyph11

import kotlin.native.getLongAt

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun wordAt(a: ByteArray, at: Int): Long = a.getLongAt(at)
