package kron.ktor

import io.ktor.events.Events
import io.ktor.events.raiseCatching
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.BaseApplicationEngine
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.withPort
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kron.IncrementalOptions
import kron.KronServer
import kron.ServerConfig
import kron.TcpOptions
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference

/** Ktor's engine factory for kron: `embeddedServer(Kron, port = 8080) { ... }`. */
object Kron : ApplicationEngineFactory<KronApplicationEngine, KronApplicationEngine.Configuration> {
    override fun configuration(configure: KronApplicationEngine.Configuration.() -> Unit): KronApplicationEngine.Configuration =
        KronApplicationEngine.Configuration().apply(configure)

    override fun create(
        environment: ApplicationEnvironment,
        monitor: Events,
        developmentMode: Boolean,
        configuration: KronApplicationEngine.Configuration,
        applicationProvider: () -> Application,
    ): KronApplicationEngine = KronApplicationEngine(environment, monitor, developmentMode, configuration, applicationProvider)
}

/**
 * Ktor on kron's reactors. Each reactor accepts its own connections and runs their calls on its own thread, from
 * the parse through the application's pipeline to the send: nothing is handed between threads, and a handler that
 * blocks holds its reactor, as on any kron server.
 *
 * Every connector listens on all IPv4 addresses, whatever its host; HTTPS connectors are refused.
 */
class KronApplicationEngine(
    environment: ApplicationEnvironment,
    monitor: Events,
    developmentMode: Boolean,
    val configuration: Configuration,
    internal val applicationProvider: () -> Application,
) : BaseApplicationEngine(environment, monitor, developmentMode) {

    /**
     * @property reactorCount reactors, one thread and one ring each; port 0 takes exactly one.
     * @property pinReactors each reactor on a CPU of its own, see [ServerConfig.pinReactors].
     * @property incremental a buffer ring per connection, see [ServerConfig.incremental].
     * @property readTimeoutMs how long a connection may wait on a silent peer; 0 disables it.
     * @property sendTimeoutMs how long a send may wait on a peer that stopped draining; 0 disables it.
     */
    class Configuration : BaseApplicationEngine.Configuration() {
        var reactorCount: Int = parallelism
        var pinReactors: Boolean = false
        var incremental: IncrementalOptions? = null
        var readTimeoutMs: Long = 60_000
        var sendTimeoutMs: Long = 60_000
    }

    private var server: KronServer? = null
    private var rootJob: Job? = null

    override fun start(wait: Boolean): ApplicationEngine {
        val connectors = configuration.connectors.toList()
        require(connectors.isNotEmpty()) { "kron needs a connector to listen on" }
        for (connector in connectors) {
            if (connector.type == ConnectorType.HTTPS) {
                throw UnsupportedOperationException("The kron engine does not support HTTPS connectors yet")
            }
        }

        val ports = connectors.map { it.port }
        require(0 !in ports || configuration.reactorCount == 1) {
            "Port 0 needs reactorCount = 1: each reactor binds its own listener, and would get a port of its own"
        }
        val config = ServerConfig(
            reactorCount = configuration.reactorCount,
            pinReactors = configuration.pinReactors,
            incremental = configuration.incremental,
            tcp = TcpOptions(
                port = ports[0],
                extraPorts = ports.drop(1).toIntArray(),
                readTimeoutMs = configuration.readTimeoutMs,
                sendTimeoutMs = configuration.sendTimeoutMs,
            ),
        )

        val parent = applicationProvider().parentCoroutineContext
        val job = SupervisorJob(parent[Job])
        rootJob = job

        // Each reactor reports in from its own thread: its ports once it listens, or why it could not start.
        val listening = AtomicInt(0)
        val fault = AtomicReference<Throwable?>(null)
        var boundPorts = IntArray(0)
        val server = KronServer(config, onStart = { reactor ->
            if (reactor.id == 0) {
                boundPorts = reactor.listenPorts
            }

            listening.addAndFetch(1)
        }) { connection ->
            Http1Session(connection, this).run()
        }

        for (reactor in server.reactors) {
            reactor.handlerContext = parent + job + ReactorDispatcher(reactor)
            reactor.onFault = { _, e -> fault.compareAndSet(null, e) }
        }

        this.server = server.start()
        while (listening.load() < server.reactors.size) {
            val cause = fault.load()
            if (cause != null) {
                server.close()
                throw cause
            }

            usleep(1_000u)
        }

        resolvedConnectorsDeferred.complete(connectors.mapIndexed { i, connector -> connector.withPort(boundPorts[i]) })
        monitor.raiseCatching(ServerReady, environment, environment.log)

        if (wait) {
            server.join()
        }

        return this
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        val server = server ?: return
        this.server = null
        monitor.raiseCatching(ApplicationStopPreparing, environment, environment.log)

        // Open connections are served to their end for the grace period; the reactors then stop and tear down.
        server.close(gracePeriodMillis)
        rootJob?.cancel()
    }
}
