package net.thunderbird.mail.testserver.proxy

import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One proxied connection: the accepted client socket, its upstream socket and one pump per direction.
 *
 * [run] performs the connect phase and then pumps client-to-server data on the calling thread; server-to-client data
 * is pumped on a thread started with [startThread].
 */
internal class ProxyConnection(
    val id: Int,
    private val client: Socket,
    private val upstreamAddress: InetSocketAddress,
    private val currentRules: () -> RuleEngine,
    transcript: Transcript,
    private val startThread: (name: String, body: () -> Unit) -> Unit,
    private val onClosed: (ProxyConnection) -> Unit,
) {
    private val transcriber = ConnectionTranscriber(id, transcript)
    private val closedLatch = CountDownLatch(1)
    private val terminating = AtomicBoolean(false)
    private val endedDirections = AtomicInteger()
    private val forwarded = Direction.entries.associateWith { AtomicLong() }

    /** Rules from `afterServerResponds` waiting for the tagged response of the command, by tag. */
    private val awaitingResponse = ConcurrentHashMap<String, FaultRule>()

    @Volatile
    private var upstream: Socket? = null

    /** The server-to-client pump; the client-to-server pump writes responses the proxy makes up through it. */
    @Volatile
    private var downstream: Pump? = null

    @Volatile
    private var stalled = false

    /** Tag of a COMPRESS or STARTTLS command; after its OK the traffic is no longer readable IMAP. */
    @Volatile
    private var opaqueStreamTag: String? = null

    @Volatile
    private var opaque = false

    fun run() {
        try {
            transcriber.event("connected from client port ${client.port}")
            val rules = currentRules()
            val connectionNumber = rules.nextConnectionNumber()
            if (rules.rules.refuseConnections) {
                transcriber.fault("refuse (rule: refuseConnections)")
                terminate("refused", reset = true)
                return
            }
            rules.matchConnect(connectionNumber)?.let { runActions(it, context = "", flush = {}) }

            val upstreamSocket = connectUpstream() ?: return
            val downstreamPump = Pump(Direction.DOWNSTREAM, upstreamSocket, client).also { downstream = it }
            startThread("c$id-down") { downstreamPump.run() }
            Pump(Direction.UPSTREAM, client, upstreamSocket).run()
        } catch (_: ConnectionEnded) {
            // Ended by a rule; already recorded.
        }
    }

    /** Closes both sides. With [reset], sockets are aborted with a TCP RST instead of a clean close. */
    fun terminate(reason: String, reset: Boolean) {
        if (!terminating.compareAndSet(false, true)) return
        listOfNotNull(client, upstream).forEach { socket ->
            if (reset) runCatching { socket.setSoLinger(true, 0) }
            runCatching { socket.close() }
        }
        val up = forwarded.getValue(Direction.UPSTREAM).get()
        val down = forwarded.getValue(Direction.DOWNSTREAM).get()
        transcriber.event("closed: $reason (forwarded $up bytes to server, $down bytes to client)")
        closedLatch.countDown()
        onClosed(this)
    }

    /** Records a fault that is not tied to a rule, e.g. [FaultProxy.disconnectAll]. */
    fun recordFault(text: String) = transcriber.fault(text)

    private fun connectUpstream(): Socket? {
        val socket = Socket()
        upstream = socket
        if (terminating.get()) {
            socket.close()
            return null
        }
        return try {
            socket.connect(upstreamAddress, UPSTREAM_CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            transcriber.event("connected upstream to ${upstreamAddress.hostString}:${upstreamAddress.port}")
            socket
        } catch (e: IOException) {
            if (!terminating.get()) {
                transcriber.fault(
                    "upstream connect to ${upstreamAddress.hostString}:${upstreamAddress.port} failed: $e",
                )
                terminate("upstream unavailable", reset = false)
            }
            null
        }
    }

    /**
     * Runs the rule's actions in order. Throws [ConnectionEnded] if an action ends the connection. Returns true if an
     * action answered the client's command ([FaultAction.Respond]) with the command's [tag], so the command must not
     * be forwarded.
     */
    private fun runActions(rule: FaultRule, context: String, flush: () -> Unit, tag: String? = null): Boolean {
        for (action in rule.actions) {
            transcriber.fault("${action.description} (rule: ${rule.description})$context")
            when (action) {
                is FaultAction.Respond -> {
                    val commandTag = checkNotNull(tag) { "respond() used outside a command rule" }
                    val pump = checkNotNull(downstream) { "respond() before the server connection was set up" }
                    pump.inject("$commandTag ${action.text}\r\n".toByteArray(Charsets.UTF_8))
                    return true
                }

                is FaultAction.Delay -> {
                    flush()
                    pause(action.duration.inWholeNanoseconds)
                }

                FaultAction.Disconnect -> endConnection(flush, "disconnected by rule", reset = false)

                FaultAction.Reset -> endConnection(flush, "reset by rule", reset = true)

                FaultAction.Refuse -> endConnection(flush, "refused by rule", reset = true)

                FaultAction.Stall -> {
                    flush()
                    stalled = true
                    awaitClose()
                }
            }
        }
        return false
    }

    private fun endConnection(flush: () -> Unit, reason: String, reset: Boolean): Nothing {
        runCatching(flush)
        terminate(reason, reset)
        throw ConnectionEnded()
    }

    /** Sleeps for [nanos]; throws [ConnectionEnded] if the connection closes meanwhile. */
    private fun pause(nanos: Long) {
        if (nanos > 0 && closedLatch.await(nanos, TimeUnit.NANOSECONDS)) throw ConnectionEnded()
    }

    private fun awaitClose(): Nothing {
        closedLatch.await()
        throw ConnectionEnded()
    }

    private fun onDirectionEnded() {
        if (endedDirections.incrementAndGet() == Direction.entries.size) terminate("both sides closed", reset = false)
    }

    @Suppress("TooManyFunctions")
    private inner class Pump(
        private val direction: Direction,
        private val source: Socket,
        private val destination: Socket,
    ) {
        private val input = source.getInputStream()
        private val output = BufferedOutputStream(destination.getOutputStream(), BUFFER_SIZE)
        private val framer = ImapFramer()
        private val buffer = ByteArray(BUFFER_SIZE)
        private val counter = forwarded.getValue(direction)
        private val sourceName = if (direction == Direction.UPSTREAM) "client" else "server"

        /** Guards [output] and [midStatement] between this pump and [inject]. */
        private val writeLock = ReentrantLock()
        private val statementEnded = writeLock.newCondition()

        /** True while a statement is partly written (a line waits for its literal or end). Guarded by [writeLock]. */
        private var midStatement = false

        /** True while the rest of a command the proxy answered itself (its literal data) is being dropped. */
        private var droppingCommand = false

        /**
         * Writes [bytes] to this pump's destination between two statements, never in the middle of one, e.g. a
         * response the proxy makes up while the server is sending a FETCH response with a literal.
         *
         * The bytes count towards this direction's forwarded bytes, so `afterBytes` rules and throttling see them like
         * forwarded data.
         */
        fun inject(bytes: ByteArray) {
            writeLock.withLock {
                while (midStatement && !terminating.get()) statementEnded.await(INJECT_WAIT_MS, TimeUnit.MILLISECONDS)
                if (terminating.get()) throw ConnectionEnded()
                write(bytes)
                output.flush()
            }
        }

        fun run() {
            try {
                while (pumpOnce()) {
                    // Keep pumping.
                }
            } catch (_: ConnectionEnded) {
                // Ended by a rule; already recorded.
            } catch (e: IOException) {
                if (!terminating.get()) {
                    transcriber.event("I/O error while forwarding from $sourceName: $e")
                    terminate("I/O error", reset = false)
                }
            } finally {
                transcriber.finish(direction)
            }
        }

        /** Reads and forwards one chunk. Returns false at the end of the stream. */
        private fun pumpOnce(): Boolean {
            source.soTimeout = if (framer.hasPartialLine) PARTIAL_LINE_FLUSH_MS else 0
            val count = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                // The source sent an unterminated line (e.g. a non-IMAP prompt) and paused. Forward what we have.
                framer.flushPartialLine()?.let(::handle)
                0
            }
            if (count > 0) forwardChunk(count)
            if (count < 0) endOfStream()
            output.flush()
            return count >= 0
        }

        private fun forwardChunk(count: Int) {
            pause(currentRules().rules.latency.inWholeNanoseconds)
            if (opaque) {
                transcriber.raw(direction, count)
                write(buffer, 0, count)
            } else {
                framer.feed(buffer, 0, count).forEach(::handle)
            }
        }

        private fun endOfStream() {
            framer.flushPartialLine()?.let(::handle)
            output.flush()
            transcriber.event("$sourceName closed its side (EOF)")
            runCatching { destination.shutdownOutput() }
            onDirectionEnded()
        }

        private fun handle(segment: FrameSegment) {
            if (droppingCommand) {
                dropCommandRest(segment)
                return
            }
            writeLock.withLock {
                forward(segment)
                midStatement = !opaque && when (segment) {
                    is FrameSegment.LiteralData -> true
                    is FrameSegment.Line -> !segment.complete || segment.literalFollows != null
                }
                if (!midStatement) statementEnded.signalAll()
            }
        }

        /** Drops literal data and continuation lines of a command the proxy answered itself. */
        private fun dropCommandRest(segment: FrameSegment) {
            when (segment) {
                is FrameSegment.LiteralData -> transcriber.literal(direction, segment)

                is FrameSegment.Line -> {
                    transcriber.line(direction, segment)
                    droppingCommand = !segment.complete || segment.literalFollows != null
                }
            }
            if (!droppingCommand) transcriber.event("dropped the rest of the command answered by the proxy")
        }

        private fun forward(segment: FrameSegment) {
            when {
                opaque -> {
                    transcriber.raw(direction, segment.bytes.size)
                    write(segment.bytes)
                }

                segment is FrameSegment.LiteralData -> {
                    transcriber.literal(direction, segment)
                    write(segment.bytes)
                }

                segment is FrameSegment.Line -> handleLine(segment)
            }
        }

        private fun handleLine(line: FrameSegment.Line) {
            transcriber.line(direction, line)
            val isStatement = !line.continuation && line.complete
            val startOpaque = when {
                !isStatement -> false

                direction == Direction.UPSTREAM -> {
                    if (onClientCommand(line)) {
                        dropAnsweredCommand(line)
                        return
                    }
                    false
                }

                else -> onServerResponse(line)
            }
            write(line.bytes)
            if (startOpaque) {
                opaque = true
                transcriber.event(
                    "stream is compressed or encrypted from here on; data is shown as byte counts and IMAP rules " +
                        "no longer apply",
                )
            }
        }

        /** Returns true if a rule answered the command, so it must not be forwarded. */
        private fun onClientCommand(line: FrameSegment.Line): Boolean {
            val command = ImapSyntax.parseCommand(line.text) ?: return false
            if (command.name == "COMPRESS" || command.name == "STARTTLS") opaqueStreamTag = command.tag

            val rule = currentRules().matchCommand(command.name, command.arguments)
            return when ((rule?.trigger as? FaultTrigger.ImapCommand)?.timing) {
                null -> false

                CommandTiming.BEFORE_SERVER_SEES ->
                    runActions(checkNotNull(rule), " - held before the server saw it", ::flush, tag = command.tag)

                CommandTiming.AFTER_SERVER_RESPONDS -> {
                    awaitingResponse[command.tag] = checkNotNull(rule)
                    false
                }
            }
        }

        /**
         * After the proxy answered a command, its literal data (if any) must not reach the server either. A client
         * only sends a synchronizing literal after a `+`, which never comes, so there is nothing to drop then.
         */
        private fun dropAnsweredCommand(line: FrameSegment.Line) {
            if (line.literalFollows == null) return
            if (ImapSyntax.endsWithSynchronizingLiteral(line.text)) {
                framer.cancelLiteral()
            } else {
                droppingCommand = true
            }
        }

        /** Returns true if the stream becomes opaque after this line. */
        private fun onServerResponse(line: FrameSegment.Line): Boolean {
            val tag = ImapSyntax.responseTag(line.text)
            if (tag != null) {
                awaitingResponse.remove(tag)?.let { rule ->
                    runActions(rule, " - server responded; response not delivered to client", ::flush)
                }
            }
            val startsOpaque = tag != null && tag == opaqueStreamTag && ImapSyntax.isOk(line.text)
            if (tag != null && tag == opaqueStreamTag) opaqueStreamTag = null

            val text = String(line.bytes, 0, line.contentLength, Charsets.UTF_8)
            currentRules().matchResponse(text)?.let { rule ->
                runActions(rule, " - before forwarding the line above", ::flush)
            }
            return startsOpaque
        }

        private fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
            val rules = currentRules()
            var position = offset
            val end = offset + length
            val start = counter.get()
            val crossed = rules.byteThresholds(direction).filter { it > start && it <= start + length }
            for (threshold in crossed) {
                val count = (threshold - counter.get()).toInt()
                writeThrottled(rules, bytes, position, count)
                position += count
                rules.matchBytes(direction, threshold)?.let { rule ->
                    runActions(rule, " - after $threshold bytes to ${direction.destinationName}", ::flush)
                }
            }
            writeThrottled(rules, bytes, position, end - position)
        }

        private fun writeThrottled(rules: RuleEngine, bytes: ByteArray, offset: Int, length: Int) {
            if (stalled) awaitClose()
            val bytesPerSecond = rules.rules.bytesPerSecond
            if (bytesPerSecond == null) {
                output.write(bytes, offset, length)
                counter.addAndGet(length.toLong())
                return
            }

            val slice = maxOf(1L, bytesPerSecond / THROTTLE_SLICES_PER_SECOND).toInt()
            var position = offset
            while (position < offset + length) {
                val count = minOf(slice, offset + length - position)
                output.write(bytes, position, count)
                output.flush()
                counter.addAndGet(count.toLong())
                position += count
                pause(TimeUnit.SECONDS.toNanos(count.toLong()) / bytesPerSecond)
            }
        }

        private fun flush() = output.flush()
    }

    private class ConnectionEnded : Exception("connection ended by a rule")

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
        const val UPSTREAM_CONNECT_TIMEOUT_MS = 10_000
        const val PARTIAL_LINE_FLUSH_MS = 200
        const val THROTTLE_SLICES_PER_SECOND = 20
        const val INJECT_WAIT_MS = 50L
    }
}

private val Direction.destinationName: String
    get() = if (this == Direction.UPSTREAM) "server" else "client"
