package net.thunderbird.mail.testserver.provision

/**
 * Reads server state with standard IMAP. Mailboxes are opened with EXAMINE and bodies fetched with BODY.PEEK, so
 * reading never changes flags or `\Recent` state.
 *
 * Folders that can't be selected (`\Noselect`, `\NonExistent`) are left out. Folders are sorted by path and messages
 * by UID. Subjects are RFC 2047-decoded; encoded words the reader can't decode are returned as sent.
 */
class DefaultServerStateReader(
    private val host: String,
    private val port: Int,
    private val timeouts: ImapTimeouts = ImapTimeouts(),
) : ServerStateReader {

    override fun read(user: ProvisionedUser): ServerState =
        openLoggedInSession(host, port, timeouts, user).use { session ->
            val delimiter = session.hierarchyDelimiter()
            val folders = session.list()
                .filter { it.isSelectable }
                .map { entry -> readFolder(session, entry.name, entry.delimiter ?: delimiter) }
                .sortedBy { it.path.toString() }
            ServerState(folders)
        }

    private fun readFolder(session: ImapSession, name: String, delimiter: Char?): ServerFolderState {
        val status = session.examine(name)
        val messages = if (status.exists == 0) emptyList() else session.uidFetchHeaders().map { it.toMessageState() }
        return ServerFolderState(
            path = folderPathFromServerName(name, delimiter),
            messages = messages.sortedBy { it.uid },
            uidValidity = status.uidValidity,
        )
    }

    private fun FetchedMessage.toMessageState(): ServerMessageState {
        val messageId = header?.let { MessageHeaders.value(it, "Message-ID") }
        return ServerMessageState(
            uid = uid,
            subject = subject,
            messageId = messageId,
            flags = flags.mapNotNull(::systemFlagFromImap).toSet(),
            keywords = flags.filterNot { it.startsWith("\\") }.toSet(),
        )
    }
}
