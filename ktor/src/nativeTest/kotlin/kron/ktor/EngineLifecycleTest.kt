package kron.ktor

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import platform.posix.SIGUSR1
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_kill
import platform.posix.pthread_tVar
import platform.posix.signal
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineLifecycleTest {
    @Test
    fun `a stop made by a signal handler on the thread waiting in start returns`() {
        // Ktor's own SIGINT and SIGTERM handler calls stop(), and the signal can land on the thread in start(wait).
        val server = embeddedServer(Kron, configure = {
            connector { port = 0 }
            reactorCount = 1
        }) {}
        stopTarget.store(server)
        signal(SIGUSR1, staticCFunction { _: Int ->
            stopTarget.load()?.stop(0, 0)
            Unit
        })

        val returned = AtomicInt(0)
        val thread = startThread {
            server.start(wait = true)
            returned.store(1)
        }

        // Signalled once it waits, which it does right after it reports its connectors.
        runBlocking { server.engine.resolvedConnectors() }
        usleep(100_000u)
        pthread_kill(thread, SIGUSR1)

        var waitedMs = 0
        while (returned.load() == 0 && waitedMs < 10_000) {
            usleep(10_000u)
            waitedMs += 10
        }

        assertEquals(1, returned.load(), "start(wait = true) never returned after the stop")
        pthread_join(thread, null)
    }

    private companion object {
        val stopTarget = AtomicReference<EmbeddedServer<*, *>?>(null)

        fun startThread(block: () -> Unit): ULong = memScoped {
            val thread = alloc<pthread_tVar>()
            val ref = StableRef.create(block)
            val rc = pthread_create(thread.ptr, null, staticCFunction { argument: COpaquePointer? ->
                val body = argument!!.asStableRef<() -> Unit>()
                try {
                    body.get()()
                } finally {
                    body.dispose()
                }

                null
            }, ref.asCPointer())
            check(rc == 0) { "pthread_create failed with $rc" }
            thread.value
        }
    }
}
