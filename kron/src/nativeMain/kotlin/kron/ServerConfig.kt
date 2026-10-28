package kron

/**
 * One reactor's settings; every reactor of a server takes the same instance.
 *
 * @property reactorCount how many reactors [KronServer] starts, one thread and one ring each.
 * @property pinReactors each reactor runs alone on one CPU: reactor i on the i-th CPU its thread may run on (the
 *   starting thread's affinity, which a container's cpuset or taskset narrows). Unpinned, the scheduler can stack
 *   two reactors on one CPU while another idles. More reactors than CPUs is refused at [KronServer.start].
 * @property ringEntries SQ entries per ring; the kernel sizes the CQ at twice that.
 * @property recvBufferSize bytes per provided buffer of the reactor's shared recv group.
 * @property recvSlots buffers in that group, a power of two up to 32768.
 * @property opSlots initial capacity of the table of in-flight timer operations; it grows on demand.
 * @property tcp the TCP transport; null opens no listener at all.
 * @property incremental a buffer ring per connection instead of one group shared by the reactor's connections, see
 *   [IncrementalOptions]; null shares one, from [recvSlots] buffers of [recvBufferSize] bytes.
 */
class ServerConfig(
    val reactorCount: Int = 12,
    val pinReactors: Boolean = false,
    val ringEntries: Int = 8192,
    val recvBufferSize: Int = 32 * 1024,
    val recvSlots: Int = 4096,
    val opSlots: Int = 4096,
    val tcp: TcpOptions? = null,
    val incremental: IncrementalOptions? = null,
)

/**
 * The incremental receive mode (Linux 6.12+, IOU_PBUF_RING_INC): a small provided-buffer ring per connection, the
 * kernel appending one recv after another into the same buffer until it is full, and a buffer back in the ring as
 * soon as its reader has handed back every slice of it.
 *
 * What it is for: a connection whose reader falls behind - a large body read as it streams in - runs dry alone. Its
 * recv pauses, its bytes wait in the kernel and TCP slows its peer, and it resumes as the reader catches up. On a
 * shared group the same connection would hold buffers every connection on the reactor draws from. The price: a
 * ring registered per connection (a syscall at every accept), [recvSlots] x [recvBufferSize] bytes each, and a cap
 * on the connections a reactor holds at once.
 *
 * @property maxConnections connections at once per reactor, one buffer group id each; accepts past it are shed.
 * @property recvSlots buffers per connection, a power of two.
 * @property recvBufferSize bytes per buffer, which the kernel fills across recvs.
 */
class IncrementalOptions(
    val maxConnections: Int = 4096,
    val recvSlots: Int = 16,
    val recvBufferSize: Int = 4096,
) {
    init {
        require(maxConnections in 1..65534) { "maxConnections must be 1 to 65534 (buffer group ids), not $maxConnections" }
        require(recvSlots > 0 && recvSlots and (recvSlots - 1) == 0 && recvSlots <= 32768) {
            "recvSlots must be a power of two up to 32768, not $recvSlots"
        }
        require(recvBufferSize > 0) { "recvBufferSize must be positive, not $recvBufferSize" }
    }
}

/**
 * @property port the listening port; 0 lets the kernel pick one (one reactor only - every reactor would get
 *   its own), read back from [Reactor.listenPorts].
 * @property extraPorts more ports, each with its own listener per reactor; [TcpConnection.listenerPort] says
 *   which one a connection came in on.
 * @property writeSlabSize initial bytes of each connection's write slab, which doubles when a response
 *   outgrows it and shrinks back on recycle.
 * @property poolMax connection objects (with their slabs) a reactor keeps for reuse.
 * @property sendWaitAll sends carry MSG_WAITALL, so the kernel completes a send only when every byte is out.
 * @property recvQueueEntries recv completions a connection can hold unread, a power of two; one more closes it.
 * @property readTimeoutMs how long a connection may wait on a silent peer, in ~250 ms steps; 0 disables it.
 * @property sendTimeoutMs how long a flush may wait on a peer that stopped draining; 0 disables it.
 */
class TcpOptions(
    val port: Int = 8080,
    val extraPorts: IntArray = IntArray(0),
    val listenBacklog: Int = 1024,
    val writeSlabSize: Int = 16 * 1024,
    val poolMax: Int = 1024,
    val sendWaitAll: Boolean = true,
    val recvQueueEntries: Int = 64,
    val readTimeoutMs: Long = 60_000,
    val sendTimeoutMs: Long = 60_000,
)
