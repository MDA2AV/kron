package playground

/** A self-signed certificate for localhost and its key, as PEM text, made at startup. */
expect object SelfSigned {
    fun generate(): Pair<String, String>
}
