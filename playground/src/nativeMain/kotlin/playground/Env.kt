package playground

import kotlinx.cinterop.toKString
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.stderr
import platform.posix.sysconf

/**
 * The PLAYGROUND_* environment knobs, the way the C# samples read them: the sample's literal is its
 * configuration, and a set variable overrides it. Unparseable values fall back to the literal.
 */
object Env {
    private fun get(name: String): String? = getenv(name)?.toKString()

    fun int(name: String, fallback: Int): Int = get(name)?.toIntOrNull() ?: fallback

    fun port(fallback: Int): Int = int("PLAYGROUND_PORT", fallback)

    fun reactors(fallback: Int): Int = int("PLAYGROUND_REACTORS", fallback)

    fun body(fallback: Int): Int = int("PLAYGROUND_BODY", fallback)

    /** 1/true/yes switch a knob on, 0/false/no off; anything else keeps the literal. */
    fun bool(name: String, fallback: Boolean): Boolean = when (get(name)?.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> fallback
    }

    fun string(name: String): String? = get(name)?.takeIf { it.isNotBlank() }

    fun processors(): Int = sysconf(_SC_NPROCESSORS_ONLN).toInt()
}

/** One line to stderr. */
fun log(message: String) {
    fputs(message + "\n", stderr)
}
