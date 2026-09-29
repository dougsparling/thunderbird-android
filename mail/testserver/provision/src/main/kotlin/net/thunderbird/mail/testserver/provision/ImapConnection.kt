package net.thunderbird.mail.testserver.provision

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Socket timeouts for the seeding/reading IMAP client, so a hung server fails a test instead of hanging it. */
data class ImapTimeouts(
    val connect: Duration = 10.seconds,
    val read: Duration = 30.seconds,
)

/** One argument of an IMAP command, written after a space. */
internal sealed interface CommandPart {
    /** Written verbatim. Callers are responsible for quoting. */
    data class Raw(val text: String) : CommandPart

    /** Sent as an IMAP literal, `{n}` plus continuation or `{n+}` with LITERAL+. */
    class Literal(val bytes: ByteArray) : CommandPart

    companion object {
        /** An astring: a quoted string when possible, a literal for CR, LF, NUL or non-ASCII characters. */
        fun string(value: String): CommandPart {
            val needsLiteral = value.any { it == '\r' || it == '\n' || it == '\u0000' || it.code > MAX_ASCII }
            return if (needsLiteral) {
                Literal(value.toByteArray(Charsets.UTF_8))
            } else {
                Raw("\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            }
        }

        /** A mailbox name: `INBOX`, or the modified UTF-7 encoding of [name], quoted. */
        fun mailbox(name: String): CommandPart =
            if (name.equals("INBOX", ignoreCase = true)) Raw("INBOX") else string(ModifiedUtf7.encode(name))

        private const val MAX_ASCII = 0x7f
    }
}

/**
 * A plaintext IMAP connection: sends tagged commands, handles literals, collects untagged responses and turns tagged
 * `NO`/`BAD` into [ImapCommandException].
 *
 * It never logs. Command arguments (which include credentials for LOGIN) are never put into exception messages.
 */
internal class ImapConnection private constructor(
    private val socket: Socket,
) : Closeable {
    private val reader = ImapResponseReader(BufferedInputStream(socket.getInputStream()))
    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream())
    private var tagCounter = 0

    /** Capabilities as last announced by the server, upper-cased. */
    var capabilities: Set<String> = emptySet()
        private set

    private fun readGreeting() {
        val greeting = reader.readResponse()
        if (greeting !is ImapResponse.UntaggedStatus || greeting.status == "BYE" || greeting.status == "NO") {
            throw ImapProtocolException("Unexpected IMAP greeting: $greeting")
        }
        capabilities = parseCapabilityCode(greeting.text) ?: fetchCapabilities()
    }

    /** Asks the server for its current capabilities and remembers them. */
    fun fetchCapabilities(): Set<String> {
        val responses = execute("CAPABILITY")
        val fromData = responses.filterIsInstance<ImapResponse.UntaggedData>()
            .firstOrNull { it.keyword() == "CAPABILITY" }
            ?.tokens?.drop(1)
            ?.mapNotNull { (it as? ImapToken.Atom)?.value?.uppercase() }
            ?.toSet()
        capabilities = fromData ?: throw ImapProtocolException("Server sent no CAPABILITY response")
        return capabilities
    }

    /**
     * Runs [command] with [parts] and returns the untagged responses received before the tagged completion.
     *
     * @param context non-sensitive detail for error messages, e.g. a folder name. Never pass credentials.
     */
    fun execute(command: String, parts: List<CommandPart> = emptyList(), context: String? = null): List<ImapResponse> {
        val tag = "A" + (++tagCounter).toString().padStart(TAG_DIGITS, '0')
        val untagged = mutableListOf<ImapResponse>()

        output.write("$tag $command".toByteArray(Charsets.US_ASCII))
        for (part in parts) {
            output.write(' '.code)
            when (part) {
                is CommandPart.Raw -> output.write(part.text.toByteArray(Charsets.UTF_8))
                is CommandPart.Literal -> writeLiteral(part.bytes, tag, command, context, untagged)
            }
        }
        output.write(CRLF)
        output.flush()

        val completion = readUntilTagged(tag, command, untagged)
        if (completion.status != "OK") {
            throw ImapCommandException(command, completion.status, completion.text, context)
        }
        parseCapabilityCode(completion.text)?.let { capabilities = it }
        return untagged
    }

    private fun writeLiteral(
        bytes: ByteArray,
        tag: String,
        command: String,
        context: String?,
        untagged: MutableList<ImapResponse>,
    ) {
        val nonSynchronizing = "LITERAL+" in capabilities ||
            ("LITERAL-" in capabilities && bytes.size <= LITERAL_MINUS_MAX_SIZE)
        if (nonSynchronizing) {
            output.write("{${bytes.size}+}".toByteArray(Charsets.US_ASCII))
            output.write(CRLF)
        } else {
            output.write("{${bytes.size}}".toByteArray(Charsets.US_ASCII))
            output.write(CRLF)
            output.flush()
            awaitContinuation(tag, command, context, untagged)
        }
        output.write(bytes)
    }

    private fun awaitContinuation(
        tag: String,
        command: String,
        context: String?,
        untagged: MutableList<ImapResponse>,
    ) {
        while (true) {
            when (val response = reader.readResponse()) {
                is ImapResponse.Continuation -> return

                is ImapResponse.Tagged -> {
                    if (response.tag != tag) throw ImapProtocolException("Unexpected tag in response to $command")
                    throw ImapCommandException(command, response.status, response.text, context)
                }

                else -> untagged.add(checkNotBye(response, command))
            }
        }
    }

    private fun readUntilTagged(
        tag: String,
        command: String,
        untagged: MutableList<ImapResponse>,
    ): ImapResponse.Tagged {
        while (true) {
            val response = reader.readResponse()
            if (response is ImapResponse.Tagged) {
                if (response.tag != tag) throw ImapProtocolException("Unexpected tag in response to $command")
                return response
            }
            if (response is ImapResponse.Continuation) {
                throw ImapProtocolException("Unexpected continuation request in response to $command")
            }
            untagged.add(if (command == "LOGOUT") response else checkNotBye(response, command))
        }
    }

    private fun checkNotBye(response: ImapResponse, command: String): ImapResponse {
        if (response is ImapResponse.UntaggedStatus && response.status == "BYE") {
            throw ImapProtocolException("Server closed the connection during $command: ${response.text}")
        }
        return response
    }

    override fun close() {
        socket.close()
    }

    companion object {
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        private const val TAG_DIGITS = 4
        private const val LITERAL_MINUS_MAX_SIZE = 4096

        fun connect(host: String, port: Int, timeouts: ImapTimeouts = ImapTimeouts()): ImapConnection {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), timeouts.connect.inWholeMilliseconds.toInt())
                socket.soTimeout = timeouts.read.inWholeMilliseconds.toInt()
                return ImapConnection(socket).also { it.readGreeting() }
            } catch (e: IOException) {
                socket.close()
                throw e
            }
        }
    }
}

internal fun ImapResponse.UntaggedData.keyword(): String? =
    (tokens.firstOrNull() as? ImapToken.Atom)?.value?.uppercase()

/** Extracts the capability list from a `[CAPABILITY ...]` response code, if present. */
internal fun parseCapabilityCode(text: String): Set<String>? =
    CAPABILITY_CODE.find(text)?.groupValues?.get(1)
        ?.split(' ')
        ?.filter { it.isNotEmpty() }
        ?.map { it.uppercase() }
        ?.toSet()

private val CAPABILITY_CODE = Regex("^\\[CAPABILITY ([^\\]]*)]", RegexOption.IGNORE_CASE)
