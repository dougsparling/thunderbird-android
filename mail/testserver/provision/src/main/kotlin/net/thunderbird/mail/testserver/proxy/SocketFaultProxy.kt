package net.thunderbird.mail.testserver.proxy

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** [FaultProxy] on plain sockets and platform threads. Every thread it starts is joined by [close]. */
internal class SocketFaultProxy(
    upstreamHost: String,
    upstreamPort: Int,
    maxTranscriptChars: Int,
) : FaultProxy {
    private val upstreamAddress = InetSocketAddress(upstreamHost, upstreamPort)
    private val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val transcript = Transcript(maxTranscriptChars)
    private val engine = AtomicReference(RuleEngine(NetworkRules.NONE))
    private val connections = ConcurrentHashMap.newKeySet<ProxyConnection>()
    private val threads = ConcurrentHashMap.newKeySet<Thread>()
    private val connectionIds = AtomicInteger()

    @Volatile
    private var closed = false

    override val port: Int = serverSocket.localPort

    override val activeRules: NetworkRules
        get() = engine.get().rules

    private val header = "Fault proxy 127.0.0.1:$port -> $upstreamHost:$upstreamPort " +
        "(times are ms since proxy start; C: client to server, S: server to client, ** event, !! fault)\n"

    init {
        transcript.record(null, "**", "listening on port $port")
        startThread("accept") { acceptLoop() }
    }

    override fun apply(rules: NetworkRules) {
        engine.set(RuleEngine(rules))
        transcript.record(null, "**", "rules applied: ${rules.describe()}")
    }

    override fun transcript(): String = header + transcript.toString()

    override fun disconnectAll(reset: Boolean) {
        val action = if (reset) FaultAction.Reset else FaultAction.Disconnect
        connections.forEach { connection ->
            connection.recordFault("${action.description} (disconnectAll)")
            connection.terminate("disconnectAll", reset)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { serverSocket.close() }
        connections.forEach { it.terminate("proxy closed", reset = false) }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_TIMEOUT_MS)
        threads.forEach { thread ->
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (thread != Thread.currentThread() && remainingMs > 0) thread.join(remainingMs)
        }
        val leaked = threads.filter { it.isAlive && it != Thread.currentThread() }
        check(leaked.isEmpty()) { "Fault proxy threads did not stop: ${leaked.joinToString { it.name }}" }
        transcript.record(null, "**", "proxy closed")
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break
            }
            socket.tcpNoDelay = true
            val connection = ProxyConnection(
                id = connectionIds.incrementAndGet(),
                client = socket,
                upstreamAddress = upstreamAddress,
                currentRules = engine::get,
                transcript = transcript,
                startThread = ::startThread,
                onClosed = { connections.remove(it) },
            )
            connections += connection
            if (closed) {
                connection.terminate("proxy closed", reset = false)
            } else {
                startThread("c${connection.id}-up") { connection.run() }
            }
        }
    }

    private fun startThread(name: String, body: () -> Unit) {
        val thread = Thread(
            {
                try {
                    body()
                } finally {
                    threads.remove(Thread.currentThread())
                }
            },
            "$THREAD_NAME_PREFIX$port-$name",
        )
        thread.isDaemon = true
        thread.setUncaughtExceptionHandler { failed, error ->
            transcript.record(null, "!!", "proxy error on ${failed.name}: $error")
            connections.filter { failed.name.contains("-c${it.id}-") }
                .forEach { it.terminate("proxy error", reset = false) }
        }
        threads += thread
        thread.start()
    }

    companion object {
        /** Every proxy thread is named `fault-proxy-<port>-<purpose>`. */
        const val THREAD_NAME_PREFIX = "fault-proxy-"
        private const val BACKLOG = 50
        private const val CLOSE_TIMEOUT_MS = 5_000L
    }
}
