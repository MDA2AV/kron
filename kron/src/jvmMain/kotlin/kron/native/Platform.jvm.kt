package kron

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path

// Threads kron did not start keep their reactor here; a ReactorThread keeps it in a field, read without a lookup.
private val reactorOfThread = ThreadLocal<Reactor?>()

internal actual var currentReactor: Reactor?
    get() {
        val thread = Thread.currentThread()
        return if (thread is ReactorThread) thread.reactor else reactorOfThread.get()
    }
    set(value) {
        val thread = Thread.currentThread()
        if (thread is ReactorThread) {
            thread.reactor = value
        } else {
            reactorOfThread.set(value)
        }
    }

internal actual fun printErr(message: String) {
    System.err.println(message)
}

internal actual fun spinWait() {
    Thread.yield()
}

internal actual fun sleepMs(milliseconds: Long) {
    Thread.sleep(milliseconds)
}

internal actual val pageSize: Long = run {
    val linker = Linker.nativeLinker()
    val getpagesize = linker.downcallHandle(linker.defaultLookup().find("getpagesize").orElseThrow(), FunctionDescriptor.of(JAVA_INT))
    (getpagesize.invokeExact() as Int).toLong()
}

internal actual fun kernelRelease(): String = System.getProperty("os.version") ?: "unknown"

internal actual fun readFileBytes(path: String): ByteArray? =
    try {
        Files.readAllBytes(Path.of(path))
    } catch (_: Exception) {
        null
    }

internal actual fun outOfMemory(message: String): Throwable = OutOfMemoryError(message)
