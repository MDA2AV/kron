package kronos

import kotlin.test.Test

/**
 * Every way a handler can be reading when it lets go, in both ring modes, against every way its client can carry on.
 * What it took has to come back through whatever holds it: the queue, or a reader's chain.
 */
class LetGoHandlerStyleTest {
    private val pong = "pong".encodeToByteArray()

    private fun runCases(handler: suspend (TcpConnection) -> Unit) {
        LetGoDriver.forEachCase { mode, ending ->
            TestServer(LetGoDriver.config(mode, ending.readTimeoutMs), handler).use { server ->
                val refuse = "RRRR".encodeToByteArray()
                LetGoDriver.check(
                    server.proactor,
                    mode,
                    ending,
                    { LetGoDriver.refused(server.port, refuse) },
                    { asked(server.port) },
                )
            }
        }
    }

    private fun asked(port: Int): Boolean = LetGoDriver.answers {
        val socket = LetGoDriver.connect(port)
        try {
            socket.write("P")
            socket.readBytes(pong.size).contentEquals(pong)
        } finally {
            socket.close()
        }
    }

    private suspend fun answer(conn: TcpConnection) {
        conn.write(pong)
        conn.flush()
    }

    // The first byte of what comes next, every buffer handed back: -1 when the connection ended with nothing.
    private suspend fun nextMarker(conn: TcpConnection): Int {
        val item = RecvItem()
        while (true) {
            val snapshot = conn.read()

            var marker = -1
            while (conn.tryGetItem(snapshot, item)) {
                if (item.hasBuffer) {
                    if (marker < 0) {
                        marker = item[0].toInt()
                    }

                    conn.returnBuffer(item)
                }
            }

            if (marker >= 0 || snapshot.isClosed) {
                return marker
            }
        }
    }

    // Answers while it's asked, and stops at anything else with what it read still where it read it.
    private suspend fun readWith(reader: PipeReader, conn: TcpConnection) {
        while (true) {
            val read = reader.read()
            if (read.buffer.isEmpty || read.buffer[0L] != 'P'.code.toByte()) {
                return
            }

            reader.advanceTo(read.buffer.length)
            answer(conn)
        }
    }

    @Test
    fun `a handler returning from raw reads gives everything back`() = runCases { conn ->
        while (nextMarker(conn) == 'P'.code) {
            answer(conn)
        }
    }

    @Test
    fun `a handler throwing from raw reads gives everything back`() = runCases { conn ->
        var marker: Int
        while (true) {
            marker = nextMarker(conn)
            if (marker != 'P'.code) {
                break
            }

            answer(conn)
        }

        if (marker >= 0) {
            throw IllegalStateException("the handler failed, for the test")
        }
    }

    @Test
    fun `a handler leaving what it read in a pipe reader gives everything back`() = runCases { conn ->
        readWith(TcpPipeReader(conn), conn)
    }

    @Test
    fun `a handler completing its pipe reader gives everything back`() = runCases { conn ->
        val reader = TcpPipeReader(conn)
        try {
            readWith(reader, conn)
        } finally {
            reader.complete()
        }
    }

    @Test
    fun `a handler leaving a read parked on its pipe reader gives everything back`() = runCases { conn ->
        val reader = TcpPipeReader(conn)
        while (true) {
            val read = reader.read()
            if (read.buffer.isEmpty) {
                break
            }

            val isAsked = read.buffer[0L] == 'P'.code.toByte()
            reader.advanceTo(read.buffer.length)
            if (!isAsked) {
                LetGoDriver.detached { reader.read() }
                break
            }

            answer(conn)
        }
    }

    @Test
    fun `a handler leaving a duplex pipe as it is gives everything back`() = runCases { conn ->
        readWith(TcpDuplexPipe(conn).input, conn)
    }
}
