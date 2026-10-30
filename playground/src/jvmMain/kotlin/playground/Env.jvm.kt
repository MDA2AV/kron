package playground

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

actual fun log(message: String) {
    System.err.println(message)
}

internal actual fun environment(name: String): String? = System.getenv(name)

// sysconf(_SC_NPROCESSORS_ONLN), as natively: the JVM's availableProcessors would count only the affinity's CPUs.
internal actual fun onlineProcessors(): Int {
    val linker = Linker.nativeLinker()
    val sysconf = linker.downcallHandle(linker.defaultLookup().find("sysconf").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_INT))
    return (sysconf.invokeExact(84) as Long).toInt()
}

actual fun exit(code: Int): Nothing = kotlin.system.exitProcess(code)
