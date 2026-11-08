package kronos

import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Connections are pooled, so a pipe kept past its handler can sit on a connection that serves the next client by
 * now, and nothing stops it from being used (#23). isValid() is how whoever kept it tells.
 */
class PipeIsValidTest {
    // The pipe's answer, then each half's.
    private fun validity(pipe: TcpDuplexPipe) = "${pipe.isValid()} ${pipe.input.isValid()} ${pipe.output.isValid()}"

    @Test
    fun `a kept pipe is valid until its client is gone and the pooled connection serves the next one`() {
        // One proactor: the handlers and the posted check run one after another on its thread.
        val firstConn = AtomicReference<TcpConnection?>(null)
        val kept = AtomicReference<TcpDuplexPipe?>(null)
        val seen = AtomicReference<List<String>>(emptyList())
        fun see(what: String) = seen.store(seen.load() + what)

        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val pipe = TcpDuplexPipe(conn)
            val old = kept.load()
            if (old == null) {
                firstConn.store(conn)
                kept.store(pipe)
            } else {
                see("same object: ${conn === firstConn.load()}")
                see("kept: ${validity(old)}")
            }
            see("own: ${validity(pipe)}")

            pipe.output.write("ok".encodeToByteArray())
            pipe.output.flush()
        }

        TestServer(testConfig(), handler).use { server ->
            server.connect().use { first ->
                assertEquals("ok", first.readToEnd().decodeToString())   // to the FIN: its handler has returned

                // Still its client's connection: the handler is gone, the client isn't.
                val checked = Latch(1)
                server.proactor.post {
                    see("handler gone: ${validity(kept.load()!!)}")
                    checked.countDown()
                }
                assertTrue(checked.await(5_000), "the posted check never ran")
            }

            // Time for the proactor to see the close and put the connection back in its pool.
            sleepMs(100)

            server.connect().use { second ->
                assertEquals("ok", second.readToEnd().decodeToString())
            }
        }

        assertEquals(
            listOf(
                "own: true true true",
                "handler gone: true true true",
                "same object: true",
                "kept: false false false",
                "own: true true true",
            ),
            seen.load(),
        )
    }
}
