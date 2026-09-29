package net.thunderbird.mail.testserver.provision

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** An argument of a command received by [FakeImapServer]. */
sealed interface FakeArg {
    data class Atom(val value: String) : FakeArg
    data class Quoted(val value: String) : FakeArg
    data class Literal(val value: String, val nonSynchronizing: Boolean) : FakeArg
    data class Group(val items: List<FakeArg>) : FakeArg

    val text: String
        get() = when (this) {
            is Atom -> value
            is Quoted -> value
            is Literal -> value
            is Group -> items.joinToString(" ", "(", ")") { it.text }
        }
}

data class ReceivedCommand(
    val tag: String,
    val name: String,
    val args: List<FakeArg>,
    val continuationsSent: Int,
)

class FakeMessage(
    val uid: Long,
    var flags: List<String>,
    val internalDate: String?,
    val content: String,
)

class FakeMailbox(val attributes: List<String> = emptyList(), val uidValidity: Long = nextUidValidity()) {
    val messages: MutableList<FakeMessage> = Collections.synchronizedList(mutableListOf())
    var nextUid = 1L

    fun add(message: FakeMessage) {
        messages.add(FakeMessage(nextUid++, message.flags, message.internalDate, message.content))
    }

    private companion object {
        private val uidValidities = java.util.concurrent.atomic.AtomicLong(0)

        fun nextUidValidity(): Long = uidValidities.incrementAndGet()
    }
}

/**
 * A small in-memory IMAP server on a local socket, good enough to drive the seeder and state reader.
 *
 * It understands the commands those and the mailbox editor use, records every command it receives, and can be told
 * to fail a command. `UID FETCH` always returns every message; `UID STORE`, `UID EXPUNGE`, `UID COPY` and `UID MOVE`
 * take a single UID.
 * CREATE does not create missing parents, so tests notice when the client forgets to create them.
 * Mailbox names are stored as sent on the wire (modified UTF-7).
 */
class FakeImapServer(
    val delimiter: Char? = '/',
    private val capabilities: List<String> = listOf("IMAP4rev1"),
    private val postLoginCapabilities: List<String> = capabilities,
    private val capabilityInGreeting: Boolean = true,
    private val sendGreeting: Boolean = true,
    /** Wire names of mailboxes that LIST sends as a literal rather than a quoted string. */
    private val listNamesAsLiteral: Set<String> = emptySet(),
) : Closeable {
    val users: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())
    val mailboxes: MutableMap<String, FakeMailbox> = Collections.synchronizedMap(linkedMapOf("INBOX" to FakeMailbox()))
    val commands: MutableList<ReceivedCommand> = Collections.synchronizedList(mutableListOf())

    /** Command name to the response status and text to answer with instead, e.g. `"CREATE" to "NO [CANNOT] nope"`. */
    val failures: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())

    private val serverSocket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
    val port: Int get() = serverSocket.localPort
    val host: String = "127.0.0.1"

    private val thread = Thread(::acceptLoop, "fake-imap").apply {
        isDaemon = true
        start()
    }

    fun commandNames(): List<String> = synchronized(commands) { commands.map { it.name } }

    fun commandsNamed(name: String): List<ReceivedCommand> = synchronized(commands) {
        commands.filter { it.name == name }
    }

    override fun close() {
        serverSocket.close()
        thread.join(1000)
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            try {
                serverSocket.accept().use(::serve)
            } catch (_: IOException) {
                // Closed by the test or the client went away.
            }
        }
    }

    private fun serve(socket: Socket) {
        val output = socket.getOutputStream()
        if (!sendGreeting) {
            socket.getInputStream().read()
            return
        }
        val code = if (capabilityInGreeting) "[CAPABILITY ${capabilities.joinToString(" ")}] " else ""
        output.writeLine("* OK ${code}Fake IMAP ready")
        val session = Session(output)
        val reader = CommandReader(BufferedInputStream(socket.getInputStream()), output)
        while (!session.loggedOut) {
            val command = reader.read() ?: return
            commands.add(command)
            session.handle(command)
        }
    }

    private inner class Session(private val output: OutputStream) {
        var loggedOut = false
        private var authenticated = false
        private var selected: FakeMailbox? = null

        @Suppress("CyclomaticComplexMethod")
        fun handle(command: ReceivedCommand) {
            val failure = failures[command.name]
            if (failure != null) {
                output.writeLine("${command.tag} $failure")
                return
            }
            val result = when (command.name) {
                "CAPABILITY" -> capability()
                "LOGIN" -> login(command.args)
                "LIST" -> list(command.args)
                "CREATE" -> create(command.args)
                "APPEND" -> append(command.args)
                "EXAMINE" -> open(command.args, "READ-ONLY")
                "SELECT" -> open(command.args, "READ-WRITE")
                "UID FETCH" -> uidFetch()
                "UID STORE" -> uidStore(command.args)
                "UID EXPUNGE" -> expunge(command.args.single().text.toLong())
                "EXPUNGE" -> expunge(uid = null)
                "UID COPY" -> uidCopy(command.args, move = false)
                "UID MOVE" -> uidCopy(command.args, move = true)
                "DELETE" -> delete(command.args)
                "RENAME" -> rename(command.args)
                "LOGOUT" -> logout()
                else -> "BAD Unsupported command"
            }
            output.writeLine("${command.tag} $result")
        }

        private fun capability(): String {
            val current = if (authenticated) postLoginCapabilities else capabilities
            output.writeLine("* CAPABILITY ${current.joinToString(" ")}")
            return "OK CAPABILITY completed"
        }

        private fun login(args: List<FakeArg>): String {
            val (user, password) = args.map { it.text }
            if (users[user] != password) return "NO [AUTHENTICATIONFAILED] Invalid credentials"
            authenticated = true
            return "OK LOGIN completed"
        }

        private fun list(args: List<FakeArg>): String {
            val delimiterText = delimiter?.let { "\"$it\"" } ?: "NIL"
            if (args[1].text.isEmpty()) {
                output.writeLine("* LIST (\\Noselect) $delimiterText \"\"")
                return "OK LIST completed"
            }
            synchronized(mailboxes) {
                for ((name, mailbox) in mailboxes) {
                    val prefix = "* LIST (${mailbox.attributes.joinToString(" ")}) $delimiterText "
                    if (name in listNamesAsLiteral) {
                        output.writeLine("$prefix{${name.length}}")
                        output.writeLine(name)
                    } else {
                        output.writeLine("$prefix\"$name\"")
                    }
                }
            }
            return "OK LIST completed"
        }

        private fun create(args: List<FakeArg>): String {
            val name = args[0].text
            val parent = delimiter?.let { name.substringBeforeLast(it, missingDelimiterValue = "") }.orEmpty()
            val error = when {
                mailboxes.containsKey(name) -> "NO [ALREADYEXISTS] Mailbox exists"
                parent.isNotEmpty() && !mailboxes.containsKey(parent) -> "NO [NONEXISTENT] Parent missing"
                else -> null
            }
            if (error != null) return error
            val use = (args.getOrNull(1) as? FakeArg.Group)?.items?.getOrNull(1) as? FakeArg.Group
            mailboxes[name] = FakeMailbox(use?.items?.map { it.text }.orEmpty())
            return "OK CREATE completed"
        }

        private fun append(args: List<FakeArg>): String {
            val mailbox = mailboxes[args[0].text] ?: return "NO [TRYCREATE] No such mailbox"
            val flags = (args.firstOrNull { it is FakeArg.Group } as? FakeArg.Group)?.items?.map { it.text }.orEmpty()
            val date = args.drop(1).firstOrNull { it is FakeArg.Quoted }?.text
            val content = args.last() as FakeArg.Literal
            mailbox.messages.add(FakeMessage(mailbox.nextUid++, flags, date, content.value))
            return "OK APPEND completed"
        }

        private fun open(args: List<FakeArg>, access: String): String {
            val mailbox = mailboxes[args[0].text] ?: return "NO [NONEXISTENT] No such mailbox"
            selected = mailbox
            output.writeLine("* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)")
            output.writeLine("* ${mailbox.messages.size} EXISTS")
            output.writeLine("* 0 RECENT")
            output.writeLine("* OK [UIDVALIDITY ${mailbox.uidValidity}] UIDs valid")
            return "OK [$access] Mailbox opened"
        }

        private fun uidStore(args: List<FakeArg>): String {
            val message = selectedMessage(args[0].text.toLong()) ?: return "BAD No such message"
            val flags = (args[2] as FakeArg.Group).items.map { it.text }
            val newFlags = when (args[1].text.uppercase()) {
                "+FLAGS.SILENT" -> (message.flags + flags).distinct()
                "-FLAGS.SILENT" -> message.flags - flags.toSet()
                else -> null
            }
            newFlags?.let { message.flags = it }
            return if (newFlags == null) "BAD Unsupported STORE" else "OK UID STORE completed"
        }

        private fun expunge(uid: Long?): String {
            val mailbox = selected ?: return "BAD No mailbox selected"
            mailbox.messages.removeAll { (uid == null || it.uid == uid) && "\\Deleted" in it.flags }
            return "OK EXPUNGE completed"
        }

        private fun uidCopy(args: List<FakeArg>, move: Boolean): String {
            val message = selectedMessage(args[0].text.toLong())
            val destination = mailboxes[args[1].text]
            return when {
                message == null -> "BAD No such message"

                destination == null -> "NO [TRYCREATE] No such mailbox"

                else -> {
                    destination.add(message)
                    if (move) selected?.messages?.remove(message)
                    "OK Done"
                }
            }
        }

        private fun delete(args: List<FakeArg>): String {
            return if (mailboxes.remove(args[0].text) ==
                null
            ) {
                "NO [NONEXISTENT] No such mailbox"
            } else {
                "OK DELETE completed"
            }
        }

        private fun rename(args: List<FakeArg>): String {
            val mailbox = mailboxes.remove(args[0].text) ?: return "NO [NONEXISTENT] No such mailbox"
            mailboxes[args[1].text] = mailbox
            return "OK RENAME completed"
        }

        private fun selectedMessage(uid: Long): FakeMessage? = selected?.messages?.firstOrNull { it.uid == uid }

        private fun uidFetch(): String {
            val mailbox = selected ?: return "BAD No mailbox selected"
            mailbox.messages.forEachIndexed { index, message ->
                val header = message.content.split("\r\n")
                    .filter { it.startsWith("Subject:", true) || it.startsWith("Message-ID:", true) }
                    .joinToString("") { "$it\r\n" } + "\r\n"
                val bytes = header.toByteArray(Charsets.UTF_8)
                output.writeLine(
                    "* ${index + 1} FETCH (UID ${message.uid} FLAGS (${message.flags.joinToString(" ")}) " +
                        "BODY[HEADER.FIELDS (SUBJECT MESSAGE-ID)] {${bytes.size}}",
                )
                output.write(bytes)
                output.writeLine(")")
            }
            return "OK UID FETCH completed"
        }

        private fun logout(): String {
            loggedOut = true
            output.writeLine("* BYE Logging out")
            return "OK LOGOUT completed"
        }
    }

    /** Reads one tagged command, answering synchronizing literals with a continuation request. */
    private class CommandReader(private val input: InputStream, private val output: OutputStream) {
        private var peeked = -1
        private var continuations = 0

        fun read(): ReceivedCommand? {
            if (peek() < 0) return null
            continuations = 0
            val tag = readAtom()
            skipSpace()
            var name = readAtom().uppercase()
            if (name == "UID") {
                skipSpace()
                name += " " + readAtom().uppercase()
            }
            val args = readArgs(endChar = '\n')
            return ReceivedCommand(tag, name, args, continuations)
        }

        private fun readArgs(endChar: Char): List<FakeArg> {
            val args = mutableListOf<FakeArg>()
            while (true) {
                skipSpace()
                when (peek()) {
                    -1 -> throw IOException("Client closed the connection")

                    '\r'.code -> next()

                    endChar.code -> {
                        next()
                        return args
                    }

                    '('.code -> {
                        next()
                        args.add(FakeArg.Group(readArgs(endChar = ')')))
                    }

                    '"'.code -> args.add(readQuoted())

                    '{'.code -> args.add(readLiteral())

                    else -> args.add(FakeArg.Atom(readAtom()))
                }
            }
        }

        private fun readQuoted(): FakeArg {
            next()
            val out = ByteArrayOutputStream()
            while (true) {
                var c = next()
                if (c == '"'.code) break
                if (c == '\\'.code) c = next()
                out.write(c)
            }
            return FakeArg.Quoted(out.toString(Charsets.UTF_8.name()))
        }

        private fun readLiteral(): FakeArg {
            next()
            val spec = StringBuilder()
            while (peek() != '}'.code) spec.append(next().toChar())
            next()
            check(next() == '\r'.code && next() == '\n'.code) { "Literal size must be followed by CRLF" }
            val nonSynchronizing = spec.endsWith("+")
            if (!nonSynchronizing) {
                continuations++
                output.writeLine("+ Ready for literal data")
            }
            val size = spec.toString().removeSuffix("+").toInt()
            val bytes = ByteArray(size) { next().toByte() }
            return FakeArg.Literal(bytes.toString(Charsets.UTF_8), nonSynchronizing)
        }

        private fun readAtom(): String {
            val builder = StringBuilder()
            var depth = 0
            var c = peek()
            while (!endsAtom(c, depth)) {
                if (c == '['.code) depth++
                if (c == ']'.code) depth--
                builder.append(next().toChar())
                c = peek()
            }
            return builder.toString()
        }

        private fun endsAtom(c: Int, depth: Int): Boolean {
            val endOfLine = c < 0 || c == '\r'.code || c == '\n'.code
            val separator = c == ' '.code || c == '('.code || c == ')'.code
            return endOfLine || (depth == 0 && separator)
        }

        private fun skipSpace() {
            while (peek() == ' '.code) next()
        }

        private fun peek(): Int {
            if (peeked == -1) peeked = input.read()
            return peeked
        }

        private fun next(): Int {
            val c = peek()
            if (c < 0) throw IOException("Client closed the connection")
            peeked = -1
            return c
        }
    }
}

private fun OutputStream.writeLine(line: String) {
    write((line + "\r\n").toByteArray(Charsets.UTF_8))
    flush()
}
