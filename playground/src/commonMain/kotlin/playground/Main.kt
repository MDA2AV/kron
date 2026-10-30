package playground


/** Runs the sample named by the first argument (default `tcp-raw`). */
fun main(args: Array<String>) {
    when (val sample = args.firstOrNull() ?: "tcp-raw") {
        "tcp-raw" -> TcpRaw.run()
        "tcp-pipe" -> TcpPipe.run()
        "tcp-count" -> TcpCount.run()
        "tcp-incremental" -> TcpIncremental.run()
        "tcp-upload" -> TcpUpload.run()
        "tls-raw" -> TlsRaw.run()
        "tls-pipe" -> TlsPipe.run()
        else -> {
            log("unknown sample '$sample'; known: tcp-raw, tcp-pipe, tcp-count, tcp-incremental, tcp-upload, tls-raw, tls-pipe")
            exit(2)
        }
    }
}
