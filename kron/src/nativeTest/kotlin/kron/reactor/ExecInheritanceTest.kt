package kron

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.linux.posix_spawnp
import platform.posix.AF_INET
import platform.posix.SIGKILL
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.kill
import platform.posix.socket
import platform.posix.waitpid
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Descriptors a host's child processes must not inherit: a child holding a server's socket keeps it open. */
class ExecInheritanceTest {
    @Test
    fun `a child process inherits none of the server's descriptors`() {
        val eventfdsBefore = descriptors("self").filterValues { it == EVENTFD }.keys
        val accepted = AtomicInt(-1)

        // Reports its descriptor once the test's client has sent, then holds the connection until the client leaves.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val item = RecvItem()
            var snapshot = conn.read()
            while (conn.tryGetItem(snapshot, item)) {
                conn.returnBuffer(item)
            }

            if (!snapshot.isClosed) {
                accepted.store(conn.clientFd)
                while (!snapshot.isClosed) {
                    snapshot = conn.read()
                    while (conn.tryGetItem(snapshot, item)) {
                        conn.returnBuffer(item)
                    }
                }
            }
        }

        TestServer(testConfig(), handler).use { server ->
            server.connect().use { client ->
                client.write(byteArrayOf(1))
                assertTrue(eventually { accepted.load() >= 0 }, "the handler never saw the test's connection")

                val mine = descriptors("self")
                val wake = mine.filterValues { it == EVENTFD }.keys - eventfdsBefore
                val opened = listOf(
                    "listener" to listenerFd(server.port, mine),
                    "accepted connection" to accepted.load(),
                    "ring" to server.reactor.ring.fd,
                    "wake eventfd" to (wake.singleOrNull() ?: -1),
                )

                // The control: a socket opened without close-on-exec is inherited, so a leak would be seen.
                val plain = socket(AF_INET, SOCK_STREAM, 0)
                val plainTarget = descriptors("self")[plain]
                val held = try {
                    heldByChild()
                } finally {
                    close(plain)
                }

                for ((kind, fd) in opened) {
                    assertTrue(mine[fd] != null, "$kind: not open in this process, so nothing was tested")
                }
                assertTrue(
                    plainTarget != null && held[plain] == plainTarget,
                    "control: the child did not inherit a plain socket, so a leak would go unseen",
                )

                // By number and target: an inherited descriptor keeps its number across the exec.
                val leaked = opened.filter { (_, fd) -> held[fd] == mine[fd] }.map { it.first }
                assertTrue(leaked.isEmpty(), "inherited by the child: ${leaked.joinToString()}")
            }
        }
    }

    // Starts a child - with posix_spawn, as this process has threads - and returns what each of its descriptors
    // refers to, then ends it.
    private fun heldByChild(): Map<Int, String> = memScoped {
        val pid = alloc<IntVar>()
        val argv = allocArray<CPointerVar<ByteVar>>(3)
        argv[0] = "sleep".cstr.ptr
        argv[1] = "30".cstr.ptr
        argv[2] = null
        val envp = allocArray<CPointerVar<ByteVar>>(1)
        envp[0] = null

        val rc = posix_spawnp(pid.ptr, "sleep", null, null, argv, envp)
        check(rc == 0) { "posix_spawnp failed with errno $rc" }

        val child = pid.value
        try {
            // posix_spawn returns once the exec has happened, so close-on-exec has been applied; the name agrees.
            check(eventually { readFileBytes("/proc/$child/comm")?.decodeToString()?.trim() == "sleep" }) {
                "the child never became sleep"
            }

            descriptors("$child")
        } finally {
            kill(child, SIGKILL)
            waitpid(child, null, 0)
        }
    }

    // What each descriptor of process [pid] refers to: "socket:[inode]", "anon_inode:[eventfd]", a path.
    private fun descriptors(pid: String): Map<Int, String> {
        val dir = platform.posix.opendir("/proc/$pid/fd") ?: return emptyMap()
        try {
            val targets = HashMap<Int, String>()
            while (true) {
                val entry = platform.posix.readdir(dir) ?: break
                val fd = entry.pointed.d_name.toKString().toIntOrNull() ?: continue
                linkTarget("/proc/$pid/fd/$fd")?.let {
                    targets[fd] = it
                }
            }

            return targets
        } finally {
            platform.posix.closedir(dir)
        }
    }

    private fun linkTarget(path: String): String? = memScoped {
        val buffer = allocArray<ByteVar>(256)
        val n = platform.posix.readlink(path, buffer, 255u)
        if (n < 0) null else buffer.readBytes(n.toInt()).decodeToString()
    }

    // This process's IPv4 socket listening on [port], or -1: /proc/net lists every socket in the namespace.
    private fun listenerFd(port: Int, mine: Map<Int, String>): Int {
        val rows = readFileBytes("/proc/self/net/tcp")?.decodeToString()?.lines()?.drop(1) ?: return -1
        for (row in rows) {
            // sl local_address rem_address st ... inode: "0100007F:4E20", state 0A is LISTEN.
            val f = row.trim().split(Regex(" +"))
            if (f.size > 9 && f[3] == "0A" && f[1].substringAfter(':').toInt(16) == port) {
                val fd = mine.entries.firstOrNull { it.value == "socket:[${f[9]}]" }?.key
                if (fd != null) {
                    return fd
                }
            }
        }

        return -1
    }

    private fun eventually(condition: () -> Boolean): Boolean {
        val deadline = Clock.monotonicMs() + 5_000
        while (Clock.monotonicMs() < deadline) {
            if (condition()) {
                return true
            }

            sleepMs(5)
        }

        return condition()
    }

    private companion object {
        const val EVENTFD = "anon_inode:[eventfd]"
    }
}
