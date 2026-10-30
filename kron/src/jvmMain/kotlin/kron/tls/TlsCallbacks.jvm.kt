package kron.tls

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

// The callbacks as upcall stubs, made once per process, each entering the common body.
internal actual object TlsCallbacks {
    private val session = ThreadLocal<TlsSession?>()

    actual var handshaking: TlsSession?
        get() = session.get()
        set(value) {
            session.set(value)
        }

    actual val keylog: Long
    actual val alpnSelect: Long
    actual val noPassphrase: Long

    init {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val long = Long::class.javaPrimitiveType
        val int = Int::class.javaPrimitiveType
        keylog = linker.upcallStub(
            lookup.findStatic(TlsCallbacks::class.java, "keylogUpcall", MethodType.methodType(Void.TYPE, long, long)),
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG), Arena.global(),
        ).address()
        alpnSelect = linker.upcallStub(
            lookup.findStatic(
                TlsCallbacks::class.java, "alpnSelectUpcall",
                MethodType.methodType(int, long, long, long, long, int, long),
            ),
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG),
            Arena.global(),
        ).address()
        noPassphrase = linker.upcallStub(
            lookup.findStatic(TlsCallbacks::class.java, "passphraseUpcall", MethodType.methodType(int, long, int, int, long)),
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG), Arena.global(),
        ).address()
    }

    @JvmStatic
    private fun keylogUpcall(ssl: Long, line: Long) = onKeylog(ssl, line)

    @JvmStatic
    private fun alpnSelectUpcall(ssl: Long, out: Long, outLength: Long, offered: Long, offeredLength: Int, arg: Long): Int =
        onAlpnSelect(ssl, out, outLength, offered, offeredLength, arg)

    // Zero bytes written fails an encrypted key's load with the ordinary error: there is never a passphrase.
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    private fun passphraseUpcall(buffer: Long, size: Int, rwflag: Int, userdata: Long): Int = 0
}
