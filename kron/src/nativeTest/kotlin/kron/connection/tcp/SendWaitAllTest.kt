package kron

import kotlin.test.Test
import kotlin.test.assertTrue

/** [TcpOptions.sendWaitAll]: whether sends carry MSG_WAITALL, set on every connection at accept, fresh or pooled. */
class SendWaitAllTest {
    // IOU_PBUF_RING_INC arrived in Linux 6.12.
    private val incrementalSupported: Boolean = run {
        val parts = kernelRelease().split('.', '-')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        major > 6 || (major == 6 && minor >= 12)
    }

    // Null leaves sendWaitAll at its default.
    private fun config(waitAll: Boolean?, incremental: Boolean = false): ServerConfig {
        val tcp = if (waitAll == null) {
            TcpOptions(port = 0, writeSlabSize = 4096, poolMax = 8)
        } else {
            TcpOptions(port = 0, writeSlabSize = 4096, poolMax = 8, sendWaitAll = waitAll)
        }

        return ServerConfig(
            reactorCount = 1,
            ringEntries = 256,
            recvBufferSize = 4096,
            recvSlots = 64,
            tcp = tcp,
            incremental = if (incremental) {
                IncrementalOptions(maxConnections = 16, recvSlots = 4, recvBufferSize = 1024)
            } else {
                null
            },
        )
    }

    // What each served connection reported, as "0 fresh" or "256 pooled": one connection at a time until a pooled one
    // is handed out, which takes the reactor having seen the last one's close first.
    private fun ask(config: ServerConfig): List<String> {
        val seen = HashSet<TcpConnection>()   // the reactor thread's alone

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val pooled = !seen.add(conn)
            val item = RecvItem()
            val snapshot = conn.read()
            while (conn.tryGetItem(snapshot, item)) {
                conn.returnBuffer(item)
            }

            conn.write("${conn.sendFlags} ${if (pooled) "pooled" else "fresh"}".encodeToByteArray())
            conn.flush()
        }

        val replies = ArrayList<String>()
        TestServer(config, handler).use { server ->
            while (replies.size < 20 && replies.none { it.endsWith(" pooled") }) {
                server.connect().use { client ->
                    client.write("?")
                    replies += client.readToEnd().decodeToString()   // up to the FIN the handler's return sends
                }
            }
        }

        return replies
    }

    private fun assertEverySend(flags: Int, replies: List<String>) {
        val all = replies.joinToString(", ")
        assertTrue(
            replies.any { it.endsWith(" fresh") } && replies.any { it.endsWith(" pooled") },
            "a fresh and a pooled connection were not both served, so one of them was never asked: $all",
        )
        assertTrue(
            replies.all { it.startsWith("$flags ") },
            "expected every connection to send with flags $flags: $all",
        )
    }

    @Test
    fun `with sendWaitAll off fresh and pooled connections send without MSG_WAITALL`() {
        assertEverySend(0, ask(config(waitAll = false)))
    }

    @Test
    fun `control - by default fresh and pooled connections send with MSG_WAITALL`() {
        assertEverySend(Sockets.MSG_WAITALL, ask(config(waitAll = null)))
    }

    @Test
    fun `with sendWaitAll off incremental-mode connections send without MSG_WAITALL too`() {
        if (!incrementalSupported) {
            println("skipped: incremental mode needs Linux 6.12, this is ${kernelRelease()}")
            return
        }

        assertEverySend(0, ask(config(waitAll = false, incremental = true)))
    }
}
