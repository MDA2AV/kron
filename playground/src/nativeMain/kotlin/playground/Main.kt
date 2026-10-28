package playground

import kotlin.system.exitProcess

/** Runs the sample named by the first argument (default `tcp-raw`). */
fun main(args: Array<String>) {
    when (val sample = args.firstOrNull() ?: "tcp-raw") {
        "tcp-raw" -> TcpRaw.run()
        "tcp-pipe" -> TcpPipe.run()
        "tcp-count" -> TcpCount.run()
        else -> {
            log("unknown sample '$sample'; known: tcp-raw, tcp-pipe, tcp-count")
            exitProcess(2)
        }
    }
}
