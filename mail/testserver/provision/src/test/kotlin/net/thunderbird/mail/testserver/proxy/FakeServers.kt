package net.thunderbird.mail.testserver.proxy

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** A command as received by [FakeImapServer], with literal data inlined. */
data class ReceivedCommand(val connection: Int, val tag: String, val name: String, val raw: String)

/**
 * A tiny scripted IMAP server. Greets, answers every command with `<tag> OK <NAME> done` unless [respond] returns
 * something else, handles synchronizing and non-synchronizing literals, and a one-step `AUTHENTICATE` exchange.
 * Independent of the proxy's own framing code.
 */
class FakeImapServer(
    private val greeting: String = "* OK fake ready\r\n",
    private val respond: (ReceivedCommand) -> ByteArray? = { null },
) : Closeable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val threads = CopyOnWriteArrayList<Thread>()
    private val _commands = CopyOnWriteArrayList<ReceivedCommand>()
    private val _authenticationData = CopyOnWriteArrayList<String>()

    val port: Int = serverSocket.localPort
    val commands: List<ReceivedCommand> get() = _commands.toList()
    val authenticationData: List<String> get() = _authenticationData.toList()
    val connectionCount: Int get() = sockets.size

    init {
        startThread {
            var connection = 0
            while (true) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                sockets += socket
                val id = ++connection
                startThread { serve(id, socket) }
            }
        }
    }

    fun commandNames(): List<String> = commands.map { it.name }

    private fun serve(connection: Int, socket: Socket) {
        socket.use {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            try {
                output.writeAscii(greeting)
                var open = true
                while (open) {
                    val command = readCommand(connection, input, output)
                    command?.let { _commands += it }
                    open = command != null && answer(command, input, output)
                }
            } catch (_: IOException) {
                // Connection ended.
            }
        }
    }

    private fun answer(command: ReceivedCommand, input: InputStream, output: OutputStream): Boolean {
        val custom = respond(command)
        when {
            custom != null -> output.write(custom)

            command.name == "AUTHENTICATE" -> {
                output.writeAscii("+ \r\n")
                _authenticationData += input.readLineOrNull() ?: throw IOException("End of stream")
                output.writeAscii("${command.tag} OK AUTHENTICATE done\r\n")
            }

            command.name == "LOGOUT" -> output.writeAscii("* BYE logging out\r\n${command.tag} OK LOGOUT done\r\n")

            else -> output.writeAscii("${command.tag} OK ${command.name} done\r\n")
        }
        output.flush()
        return command.name != "LOGOUT"
    }

    private fun readCommand(connection: Int, input: InputStream, output: OutputStream): ReceivedCommand? {
        val firstLine = input.readLineOrNull() ?: return null
        val raw = StringBuilder(firstLine)
        var literal = LITERAL.find(firstLine)
        while (literal != null) {
            if (!literal.value.contains('+')) output.writeAscii("+ go ahead\r\n")
            raw.append("\r\n").append(String(input.readExactly(literal.groupValues[1].toInt()), Charsets.ISO_8859_1))
            val line = input.readLineOrNull() ?: throw IOException("End of stream inside a command")
            raw.append(line)
            literal = LITERAL.find(line)
        }
        val parts = raw.toString().split(' ')
        val name = if (parts.getOrNull(1).equals("UID", ignoreCase = true)) {
            "UID " + parts.getOrNull(2).orEmpty().uppercase()
        } else {
            parts.getOrNull(1).orEmpty().uppercase()
        }
        return ReceivedCommand(connection, parts[0], name, raw.toString())
    }

    private fun startThread(body: () -> Unit) {
        threads += Thread(body, "fake-imap-server").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        serverSocket.close()
        sockets.forEach { runCatching { it.close() } }
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
    }

    private companion object {
        val LITERAL = Regex("""\{(\d+)\+?}$""")
    }
}

/** Echoes every byte back on each connection. */
class EchoServer : Closeable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private val thread = Thread({ acceptLoop() }, "echo-server").apply {
        isDaemon = true
        start()
    }

    val port: Int = serverSocket.localPort

    private fun acceptLoop() {
        while (true) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break
            }
            sockets += socket
            Thread({ socket.use { runCatching { it.getInputStream().copyTo(it.getOutputStream()) } } }, "echo").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun close() {
        serverSocket.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        thread.join(TimeUnit.SECONDS.toMillis(5))
    }
}

/** A blocking test client with a read timeout so a broken test fails instead of hanging. */
class TestClient(port: Int, readTimeoutMs: Int = 5_000) : Closeable {
    val socket = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = readTimeoutMs }
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    fun send(text: String) = send(text.toByteArray(Charsets.ISO_8859_1))

    fun send(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /** Reads one line without its CRLF, or null at end of stream. */
    fun readLine(): String? = input.readLineOrNull()

    fun readExactly(count: Int): ByteArray = input.readExactly(count)

    /** Reads until the server closes the connection. Throws on reset or timeout. */
    fun readToEnd(): ByteArray = input.readBytes()

    /** Sends a command and returns all response lines up to and including the tagged one. */
    fun command(tag: String, command: String): List<String> {
        send("$tag $command\r\n")
        return buildList {
            while (true) {
                val line = readLine() ?: error("Connection closed while waiting for $tag")
                add(line)
                if (line.startsWith("$tag ")) break
            }
        }
    }

    override fun close() = socket.close()
}

internal fun OutputStream.writeAscii(text: String) {
    write(text.toByteArray(Charsets.ISO_8859_1))
    flush()
}

internal fun InputStream.readLineOrNull(): String? {
    val line = ByteArrayOutputStream()
    while (true) {
        val value = read()
        if (value < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
        if (value == '\n'.code) break
        line.write(value)
    }
    return line.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
}

internal fun InputStream.readExactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var position = 0
    while (position < count) {
        val read = read(bytes, position, count - position)
        if (read < 0) throw IOException("End of stream after $position of $count bytes")
        position += read
    }
    return bytes
}
