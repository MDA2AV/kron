package playground


/**
 * The PLAYGROUND_* environment knobs, the way the C# samples read them: the sample's literal is its
 * configuration, and a set variable overrides it. Unparseable values fall back to the literal.
 */
object Env {
    private fun get(name: String): String? = environment(name)

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

    fun processors(): Int = onlineProcessors()
}

/** One line to stderr. */
expect fun log(message: String)

/** Ends the process with [code]. */
expect fun exit(code: Int): Nothing

internal expect fun environment(name: String): String?

// The CPUs online, as sysconf counts them: not narrowed by the process's affinity on either platform.
internal expect fun onlineProcessors(): Int
