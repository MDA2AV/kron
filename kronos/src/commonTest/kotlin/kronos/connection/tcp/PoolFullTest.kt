package kronos

import kotlin.test.Test
import kotlin.test.assertTrue

/** With the pool full, a recycled connection's memory goes right there rather than waiting for the stop. */
class PoolFullTest {
    @Test
    fun `a connection recycled into a full pool has its write slab freed right there`() {
        val conns = ArrayList<TcpConnection>()   // filled on the proactor thread before the latches count down
        val bothIn = Latch(2)
        val released = Latch(2)

        val config = ServerConfig(
            proactorCount = 1,
            ringEntries = 256,
            recvBufferSize = 4096,
            recvSlots = 64,
            tcp = TcpOptions(port = 0, poolMax = 1),
        )

        TestServer(config) { conn ->
            val item = RecvItem()
            var request = true
            while (true) {
                val snapshot = conn.read()
                while (conn.tryGetItem(snapshot, item)) {
                    conn.returnBuffer(item)
                }

                if (request) {
                    request = false
                    conns.add(conn)
                    bothIn.countDown()
                }
                if (snapshot.isClosed) {
                    break
                }
            }

            conn.decRef()   // the proactor let go when the client closed: this recycles it, here
            released.countDown()
        }.use { server ->
            val first = server.connect()
            val second = server.connect()
            first.write("a")
            second.write("b")
            assertTrue(bothIn.await(5_000), "the two handlers never ran")

            first.close()
            second.close()
            assertTrue(released.await(5_000), "the handlers never saw their clients close")

            // Both recycled while the proactor runs: the pool takes one, so the other was freed on the spot.
            val freed = conns.count { it.writeBuffer == 0L }
            assertTrue(freed >= 1, "$freed of the two recycled connections had their slab freed with the pool full")
        }
    }
}
