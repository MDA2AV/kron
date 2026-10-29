package kron

import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [Reactor.handlerContext]: the context a handler's coroutine starts with, kept across every suspension. */
class HandlerContextTest {
    private class Tag(val name: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Tag>
    }

    @Test
    fun `an element set on handlerContext is in the handler's context at start and after each read flush and delay`() {
        val trail = AtomicReference<String?>(null)

        // Each step is a real suspension: the first read always parks (its recv is armed after the handler starts),
        // and the client sends each next message only once the step before has answered.
        val handler: suspend (TcpConnection) -> Unit = { conn ->
            val steps = ArrayList<String>()
            suspend fun look(step: String) {
                steps.add("$step=${coroutineContext[Tag]?.name}")
            }

            look("start")
            val item = RecvItem()
            val snapshot = conn.read()
            look("read")
            while (conn.tryGetItem(snapshot, item)) {
                conn.returnBuffer(item)
            }

            conn.write("1".encodeToByteArray())
            conn.flush()
            look("flush")

            RingTimer(conn.reactor).delay(1)
            look("delay")

            conn.write("2".encodeToByteArray())
            conn.flush()

            // The pipe parks continuations of its own on the connection, then resumes the handler's.
            val pipe = TcpDuplexPipe(conn)
            val result = pipe.input.read()
            look("pipe read")
            pipe.input.advanceTo(result.buffer.length)

            pipe.output.write("3".encodeToByteArray())
            pipe.output.flush()
            look("pipe flush")

            pipe.input.complete()
            trail.store(steps.joinToString(", "))
        }

        val ready = Latch(1)
        val server = KronServer(
            testConfig(),
            onStart = { reactor ->
                reactor.handlerContext = Tag("reactor ${reactor.id}")
                ready.countDown()
            },
            handler = handler,
        ).start()

        try {
            assertTrue(ready.await(10_000), "the reactor never started")
            TestSocket().use { client ->
                client.setReceiveTimeout(5_000)
                client.connect(server.reactors[0].listenPorts[0])

                client.write("one")
                assertEquals("12", client.readBytes(2).decodeToString())
                client.write("two")
                assertEquals("3", client.readBytes(1).decodeToString())
                assertEquals(-1, client.read(), "the handler did not end")
            }
        } finally {
            server.close()
        }

        val tag = "reactor 0"
        assertEquals(
            "start=$tag, read=$tag, flush=$tag, delay=$tag, pipe read=$tag, pipe flush=$tag",
            trail.load(),
        )
    }
}
