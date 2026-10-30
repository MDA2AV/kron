package kron

// Raw memory at Long addresses, as the port holds every native pointer: through cinterop on Kotlin/Native, through
// one segment over the whole address space on the JVM. The acquire and release forms pair with the kernel's side of a
// ring index (smp_load_acquire, smp_store_release).

internal expect fun getByte(addr: Long): Byte

internal expect fun putByte(addr: Long, v: Byte)

internal expect fun getShort(addr: Long): Short

internal expect fun putShort(addr: Long, v: Short)

internal expect fun getInt(addr: Long): Int

internal expect fun putInt(addr: Long, v: Int)

internal expect fun getLong(addr: Long): Long

internal expect fun putLong(addr: Long, v: Long)

internal expect fun getIntAcquire(addr: Long): Int

internal expect fun putIntRelease(addr: Long, v: Int)

internal expect fun putShortRelease(addr: Long, v: Short)

/** Copies [length] bytes from [src] to [dst]; the ranges may overlap. */
internal expect fun copyMemory(src: Long, dst: Long, length: Long)

internal expect fun copyFromArray(src: ByteArray, srcOffset: Int, dst: Long, length: Int)

internal expect fun copyToArray(src: Long, dst: ByteArray, dstOffset: Int, length: Int)

internal expect fun zeroMemory(addr: Long, length: Long)

/**
 * Native memory by address, for the raw seam: what [RecvItem.address], [ReadBuffer.segmentAddress] and
 * [TcpConnection.getBuffer] point into. Nothing is checked, so stay inside what the item, segment or buffer
 * covers.
 */
object Memory {
    fun getByte(address: Long): Byte = kron.getByte(address)
    fun putByte(address: Long, value: Byte) = kron.putByte(address, value)
    fun getShort(address: Long): Short = kron.getShort(address)
    fun putShort(address: Long, value: Short) = kron.putShort(address, value)
    fun getInt(address: Long): Int = kron.getInt(address)
    fun putInt(address: Long, value: Int) = kron.putInt(address, value)
    fun getLong(address: Long): Long = kron.getLong(address)
    fun putLong(address: Long, value: Long) = kron.putLong(address, value)

    /** Copies [length] bytes of [source] from [sourceOffset] to [destination]. */
    fun copy(source: ByteArray, sourceOffset: Int, destination: Long, length: Int) =
        copyFromArray(source, sourceOffset, destination, length)

    /** Copies [length] bytes from [source] into [destination] at [destinationOffset]. */
    fun copy(source: Long, destination: ByteArray, destinationOffset: Int, length: Int) =
        copyToArray(source, destination, destinationOffset, length)

    /** Copies [length] bytes between native addresses. */
    fun copy(source: Long, destination: Long, length: Long) = copyMemory(source, destination, length)
}
