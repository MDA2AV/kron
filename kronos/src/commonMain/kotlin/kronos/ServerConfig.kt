package kronos

/**
 * One proactor's settings; every proactor of a server takes the same instance.
 *
 * @property proactorCount how many proactors [KronosServer] starts, one thread and one ring each.
 * @property pinProactors each proactor runs alone on one CPU: proactor i on the i-th CPU its thread may run on (the
 *   starting thread's affinity, which a container's cpuset or taskset narrows). Unpinned, the scheduler can stack
 *   two proactors on one CPU while another idles. More proactors than CPUs is refused at [KronosServer.start].
 * @property ringEntries SQ entries per ring; the kernel sizes the CQ at twice that.
 * @property recvBufferSize bytes per provided buffer of the proactor's shared recv group.
 * @property recvSlots buffers in that group, a power of two up to 32768.
 * @property opSlots initial capacity of the table of in-flight timer operations; it grows on demand.
 * @property tcp the TCP transport; null opens no listener at all.
 * @property incremental a buffer ring per connection instead of one group shared by the proactor's connections, see
 *   [IncrementalOptions]; null shares one, from [recvSlots] buffers of [recvBufferSize] bytes.
 */
class ServerConfig(
    val proactorCount: Int = 12,
    val pinProactors: Boolean = false,
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
 * shared group the same connection would hold buffers every connection on the proactor draws from. The price: a
 * ring registered per connection (a syscall at every accept), [recvSlots] x [recvBufferSize] bytes each, and a cap
 * on the connections a proactor holds at once.
 *
 * @property maxConnections connections at once per proactor, one buffer group id each; accepts past it are shed.
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
 * @property port the listening port; 0 lets the kernel pick one (one proactor only - every proactor would get
 *   its own), read back from [Proactor.listenPorts].
 * @property extraPorts more ports, each with its own listener per proactor; [TcpConnection.listenerPort] says
 *   which one a connection came in on.
 * @property writeSlabSize initial bytes of each connection's write slab, which doubles when a response
 *   outgrows it and shrinks back on recycle.
 * @property poolMax connection objects (with their slabs) a proactor keeps for reuse.
 * @property sendWaitAll sends carry MSG_WAITALL, so the kernel completes a send only when every byte is out.
 * @property recvQueueEntries recv completions a connection can hold unread, a power of two; one more closes it.
 * @property readTimeoutMs how long a read may wait on a silent peer, in ~250 ms steps; 0 disables it. The clock runs
 *   only while a read waits with nothing queued, and a flush restarts it, so a handler that is busy, sending or not
 *   reading is never timed out by it. It also bounds how long a peer has to close after its handler let go.
 *   [TcpConnection.disableReadTimeout] takes a connection's reads off it.
 * @property sendTimeoutMs how long one flush may take to go out, in ~250 ms steps; 0 disables it. It is there for a
 *   peer that stopped reading: once the socket's buffer is full its send never completes, and the flush would hold
 *   the connection and its slab for good. It is a deadline for the whole flush, not for progress within it, and
 *   nothing the peer sends restarts it, so a peer that reads a little now and then can't hold a flush open either.
 *   Set it against the slowest legitimate flush, not against a stall: a large body to a slow peer is the false
 *   positive. Flushing such a body in pieces gives each piece its own deadline, so the peer then only has to keep
 *   up a minimum rate, the piece size per this timeout.
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
