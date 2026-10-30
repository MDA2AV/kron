package playground

import kotlinx.cinterop.toKString
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.stderr
import platform.posix.sysconf

actual fun log(message: String) {
    fputs(message + "\n", stderr)
}

internal actual fun environment(name: String): String? = getenv(name)?.toKString()

internal actual fun onlineProcessors(): Int = sysconf(_SC_NPROCESSORS_ONLN).toInt()

actual fun exit(code: Int): Nothing = kotlin.system.exitProcess(code)
