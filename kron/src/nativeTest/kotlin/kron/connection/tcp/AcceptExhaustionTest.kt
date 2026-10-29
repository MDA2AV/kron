package kron

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.linux.RLIMIT_NOFILE
import platform.linux.getrlimit
import platform.linux.rlimit
import platform.linux.setrlimit
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix._SC_CLK_TCK
import platform.posix.close
import platform.posix.errno
import platform.posix.ioctl
import platform.posix.open
import platform.posix.pread
import platform.posix.sysconf
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A reactor that runs out of file descriptors while connections wait in its listen backlog. */
class AcceptExhaustionTest {
    @Test
    fun `out of file descriptors a reactor stops accepting instead of spinning - then resumes`() {
        // Made before the limit drops: connecting needs no new descriptor, accepting does.
        val clients = List(CLIENTS) {
            TestSocket().also {
                it.setReceiveTimeout(5_000)
            }
        }

        val greeter: suspend (TcpConnection) -> Unit = { conn ->
            conn.write("hi".encodeToByteArray())
            conn.flush()
            conn.read()   // the client only closes
        }

        val stat = AtomicInt(-1)
        val refused = AtomicReference<String?>(null)
        val ready = Latch(1)

        memScoped {
            val original = alloc<rlimit>()
            assertEquals(0, getrlimit(RLIMIT_NOFILE, original.ptr))
            val hardLimit = original.rlim_max

            val server = KronServer(
                testConfig(),
                onStart = {
                    // Here, before the accept is armed: io_uring reads the limit when it arms an accept, so an armed
                    // multishot accept would never see a lower one. Room for six accepts.
                    stat.store(open("/proc/thread-self/stat", O_RDONLY or O_CLOEXEC))
                    memScoped {
                        val tight = alloc<rlimit>()
                        tight.rlim_cur = limitLeavingFree(6).convert()
                        tight.rlim_max = hardLimit
                        if (setrlimit(RLIMIT_NOFILE, tight.ptr) != 0) {
                            refused.store("setrlimit failed with errno $errno")
                        }
                    }
                    ready.countDown()
                },
                handler = greeter,
            ).start()

            try {
                var busy: Double
                var greetedEarly: Int

                // The window the whole process runs short of descriptors in: kept short, and always closed.
                try {
                    assertTrue(ready.await(10_000), "the reactor never started")
                    assertNull(refused.load())

                    // The kernel completes every handshake; the reactor can accept a few of them.
                    val port = server.reactors[0].listenPorts[0]
                    for (client in clients) {
                        client.connect(port)
                    }

                    sleepMs(300)
                    val before = cpuTicks(stat.load())
                    sleepMs(1_000)
                    busy = (cpuTicks(stat.load()) - before).toDouble() / sysconf(_SC_CLK_TCK)   // of one core

                    greetedEarly = clients.count { available(it) > 0 }
                } finally {
                    setrlimit(RLIMIT_NOFILE, original.ptr)
                    close(stat.load())
                }

                // With descriptors back, every waiting connection is accepted and greeted.
                val greeted = clients.count { client ->
                    try {
                        client.readBytes(2).decodeToString() == "hi"
                    } catch (e: IOException) {
                        false
                    }
                }

                assertTrue(
                    greetedEarly < CLIENTS,
                    "every connection was accepted under the tight limit, so it never ran out and nothing was tested",
                )
                assertTrue(
                    busy < 0.3,
                    "the reactor used ${(busy * 100).toInt()}% of a core while out of descriptors: " +
                        "it kept re-arming an accept that could only fail",
                )
                assertEquals(CLIENTS, greeted, "connections greeted once descriptors were back")
            } finally {
                clients.forEach {
                    it.close()
                }
                server.close()
            }
        }
    }

    // RLIMIT_NOFILE caps descriptor numbers, not how many are open, and earlier tests leave holes in the table: the
    // limit that leaves exactly [free] numbers free below it. The listing's own descriptor is not counted as taken.
    private fun limitLeavingFree(free: Int): Long {
        val open = HashSet<Int>()
        val dir = platform.posix.opendir("/proc/self/fd") ?: error("opendir(/proc/self/fd) failed")
        try {
            val own = platform.posix.dirfd(dir)
            while (true) {
                val entry = platform.posix.readdir(dir) ?: break
                val fd = entry.pointed.d_name.toKString().toIntOrNull() ?: continue
                if (fd != own) {
                    open.add(fd)
                }
            }
        } finally {
            platform.posix.closedir(dir)
        }

        var left = free
        var n = 0
        while (true) {
            if (n !in open && --left == 0) {
                return n + 1L
            }
            n++
        }
    }

    // utime + stime, in clock ticks, of the thread whose stat [fd] is; procfs regenerates it for a read at 0.
    private fun cpuTicks(fd: Int): Long {
        val buffer = ByteArray(1024)
        val n = buffer.usePinned { pread(fd, it.addressOf(0), buffer.size.convert(), 0) }
        check(n > 0) { "reading the reactor thread's stat failed with errno $errno" }

        val text = buffer.decodeToString(0, n.toInt())
        val fields = text.substring(text.lastIndexOf(')') + 2).split(' ')
        return fields[11].toLong() + fields[12].toLong()
    }

    // Bytes waiting to be read on [socket], without reading them.
    private fun available(socket: TestSocket): Int = memScoped {
        val bytes = alloc<IntVar>()
        ioctl(socket.fd, FIONREAD, bytes.ptr)
        bytes.value
    }

    private companion object {
        const val CLIENTS = 24

        // From asm-generic/ioctls.h, the same on x86-64 and arm64.
        const val FIONREAD = 0x541BUL
    }
}
