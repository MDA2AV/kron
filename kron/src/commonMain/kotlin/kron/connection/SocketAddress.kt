package kron

/** One end of a TCP connection: an IP address in text form, IPv4 or IPv6, and a port. */
data class SocketAddress(val host: String, val port: Int) {
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"
}
