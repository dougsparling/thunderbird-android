package net.thunderbird.mail.testserver.provision

import java.io.Closeable
import java.io.IOException

/** One entry of a LIST response. [name] is decoded from modified UTF-7; [attributes] are upper-cased. */
internal data class ListEntry(
    val name: String,
    val delimiter: Char?,
    val attributes: Set<String>,
) {
    val isSelectable: Boolean
        get() = "\\NOSELECT" !in attributes && "\\NONEXISTENT" !in attributes
}

/** A message from `UID FETCH`. [flags] keep the server's spelling; [header] is the raw fetched header block. */
internal class FetchedMessage(
    val uid: Long,
    val flags: List<String>,
    val header: ByteArray?,
)

/**
 * High-level IMAP commands used by the seeder and the state reader, on top of [ImapConnection].
 *
 * This is deliberately independent of the app's IMAP implementation, so a bug there can't hide itself in the harness.
 */
internal class ImapSession(private val connection: ImapConnection) : Closeable {
    val capabilities: Set<String> get() = connection.capabilities

    fun login(username: String, password: String) {
        if ("LOGINDISABLED" in connection.capabilities) {
            throw ImapProtocolException("Server advertises LOGINDISABLED; plaintext LOGIN is not possible")
        }
        connection.execute("LOGIN", listOf(CommandPart.string(username), CommandPart.string(password)))
        // Capabilities often grow after authentication (e.g. CREATE-SPECIAL-USE), so ask again.
        connection.fetchCapabilities()
    }

    /**
     * `LIST "" ""`: the hierarchy delimiter, or null for a flat server. Falls back to [list] if the server says
     * nothing.
     */
    fun hierarchyDelimiter(): Char? {
        val root = parseListEntries(connection.execute("LIST", listOf(EMPTY, EMPTY)))
        return root.firstOrNull()?.delimiter ?: list().firstNotNullOfOrNull { it.delimiter }
    }

    fun list(): List<ListEntry> = parseListEntries(connection.execute("LIST", listOf(EMPTY, CommandPart.Raw("\"*\""))))

    fun create(name: String, specialUse: String?) {
        val parts = buildList {
            add(CommandPart.mailbox(name))
            if (specialUse != null) add(CommandPart.Raw("(USE ($specialUse))"))
        }
        connection.execute("CREATE", parts, context = name)
    }

    /** Opens [name] read-only and returns the number of messages in it. */
    fun examine(name: String): Int {
        val responses = connection.execute("EXAMINE", listOf(CommandPart.mailbox(name)), context = name)
        return responses.filterIsInstance<ImapResponse.UntaggedData>()
            .lastOrNull { it.tokens.size >= 2 && (it.tokens[1] as? ImapToken.Atom)?.value.equals("EXISTS", true) }
            ?.let { (it.tokens[0] as ImapToken.Atom).value.toInt() }
            ?: throw ImapProtocolException("EXAMINE response for '$name' had no EXISTS count")
    }

    /**
     * @param flags system flags (`\Seen`) and keywords, written as given.
     * @param internalDate an RFC 3501 date-time without quotes, or null to let the server choose.
     */
    fun append(name: String, flags: List<String>, internalDate: String?, content: ByteArray) {
        val parts = buildList {
            add(CommandPart.mailbox(name))
            if (flags.isNotEmpty()) add(CommandPart.Raw(flags.joinToString(" ", prefix = "(", postfix = ")")))
            if (internalDate != null) add(CommandPart.Raw("\"$internalDate\""))
            add(CommandPart.Literal(content))
        }
        connection.execute("APPEND", parts, context = name)
    }

    /** Fetches UID, flags and the Subject/Message-ID headers of every message in the selected mailbox. */
    fun uidFetchHeaders(): List<FetchedMessage> {
        val responses = connection.execute(
            "UID FETCH",
            listOf(
                CommandPart.Raw("1:*"),
                CommandPart.Raw("(UID FLAGS BODY.PEEK[HEADER.FIELDS (SUBJECT MESSAGE-ID)])"),
            ),
        )
        return parseFetchResponses(responses)
    }

    fun logout() {
        connection.execute("LOGOUT")
    }

    /** Logs out politely if possible and closes the socket in any case. */
    override fun close() {
        try {
            logout()
        } catch (_: IOException) {
            // The connection is being discarded; a failed LOGOUT changes nothing.
        } finally {
            connection.close()
        }
    }

    private companion object {
        val EMPTY = CommandPart.Raw("\"\"")
    }
}

internal fun parseListEntries(responses: List<ImapResponse>): List<ListEntry> =
    responses.filterIsInstance<ImapResponse.UntaggedData>()
        .filter { it.keyword() == "LIST" && it.tokens.size >= LIST_MIN_TOKENS }
        .map { response ->
            val attributes = (response.tokens[1] as? ImapToken.ListNode)?.items.orEmpty()
                .mapNotNull { (it as? ImapToken.Atom)?.value?.uppercase() }
                .toSet()
            val delimiter = (response.tokens[2] as? ImapToken.Str)?.text?.singleOrNull()
            val rawName = when (val token = response.tokens[LIST_NAME_INDEX]) {
                is ImapToken.Atom -> token.value
                is ImapToken.Str -> token.text
                ImapToken.Nil -> "NIL"
                is ImapToken.ListNode -> throw ImapProtocolException("Unexpected list as mailbox name in LIST response")
            }
            ListEntry(decodeMailboxName(rawName), delimiter, attributes)
        }

private fun decodeMailboxName(raw: String): String =
    try {
        ModifiedUtf7.decode(raw)
    } catch (_: IllegalArgumentException) {
        // Not valid modified UTF-7 (e.g. a server sending raw UTF-8); use the name as sent.
        raw
    }

/** Merges FETCH responses by sequence number, since a server may split one message's data over several responses. */
internal fun parseFetchResponses(responses: List<ImapResponse>): List<FetchedMessage> {
    val bySequence = linkedMapOf<String, MutableMap<String, ImapToken>>()
    responses.filterIsInstance<ImapResponse.UntaggedData>()
        .filter { it.tokens.size >= FETCH_MIN_TOKENS && (it.tokens[1] as? ImapToken.Atom)?.value.equals("FETCH", true) }
        .forEach { response ->
            val sequence = (response.tokens[0] as ImapToken.Atom).value
            val items = (response.tokens[2] as? ImapToken.ListNode)?.items
                ?: throw ImapProtocolException("FETCH response without data list")
            val merged = bySequence.getOrPut(sequence) { mutableMapOf() }
            items.chunked(2).filter { it.size == 2 }.forEach { (key, value) ->
                val name = (key as? ImapToken.Atom)?.value?.uppercase()
                    ?: throw ImapProtocolException("FETCH response with non-atom item name")
                merged[if (name.startsWith("BODY[")) "BODY[]" else name] = value
            }
        }

    return bySequence.values.mapNotNull { items ->
        val uid = (items["UID"] as? ImapToken.Atom)?.value?.toLongOrNull() ?: return@mapNotNull null
        val flags = (items["FLAGS"] as? ImapToken.ListNode)?.items.orEmpty().mapNotNull {
            (it as? ImapToken.Atom)?.value
        }
        FetchedMessage(uid, flags, (items["BODY[]"] as? ImapToken.Str)?.bytes)
    }
}

private const val LIST_MIN_TOKENS = 4
private const val LIST_NAME_INDEX = 3
private const val FETCH_MIN_TOKENS = 3
