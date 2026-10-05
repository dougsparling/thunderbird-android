package net.thunderbird.mail.testserver.provision

import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * [MailboxEditor] with standard IMAP commands only. Each call uses its own connection, so it never shares state with
 * the app under test or with other helpers. Messages are looked up with `BODY.PEEK`, so finding one doesn't mark it
 * read.
 */
class DefaultMailboxEditor(
    private val host: String,
    private val port: Int,
    private val timeouts: ImapTimeouts = ImapTimeouts(),
) : MailboxEditor {

    override fun deleteMessage(user: ProvisionedUser, folder: FolderPath, subject: String, expunge: Boolean) {
        withMessage(user, folder, subject) { session, _, uid ->
            session.uidStore(uid, ImapSession.ADD_FLAGS, listOf(SystemFlag.DELETED.imapName))
            if (expunge) session.expunge(uid)
        }
    }

    override fun moveMessage(user: ProvisionedUser, from: FolderPath, subject: String, to: FolderPath) {
        withMessage(user, from, subject) { session, delimiter, uid ->
            session.uidMove(uid, to.toServerName(delimiter))
        }
    }

    override fun setFlags(
        user: ProvisionedUser,
        folder: FolderPath,
        subject: String,
        add: Set<SystemFlag>,
        remove: Set<SystemFlag>,
    ) {
        withMessage(user, folder, subject) { session, _, uid ->
            session.uidStore(uid, ImapSession.ADD_FLAGS, add.map { it.imapName })
            session.uidStore(uid, ImapSession.REMOVE_FLAGS, remove.map { it.imapName })
        }
    }

    override fun deleteFolder(user: ProvisionedUser, path: FolderPath) {
        require(!path.isInbox) { "INBOX can't be deleted" }
        withSession(user) { session, delimiter -> session.delete(path.toServerName(delimiter)) }
    }

    override fun renameFolder(user: ProvisionedUser, from: FolderPath, to: FolderPath) {
        withSession(user) { session, delimiter ->
            session.rename(from.toServerName(delimiter), to.toServerName(delimiter))
        }
    }

    private fun withSession(user: ProvisionedUser, block: (ImapSession, Char?) -> Unit) {
        openLoggedInSession(host, port, timeouts, user).use { session ->
            block(session, session.hierarchyDelimiter())
        }
    }

    private fun withMessage(
        user: ProvisionedUser,
        folder: FolderPath,
        subject: String,
        block: (session: ImapSession, delimiter: Char?, uid: Long) -> Unit,
    ) {
        withSession(user) { session, delimiter ->
            val status = session.select(folder.toServerName(delimiter))
            val messages = if (status.exists == 0) emptyList() else session.uidFetchHeaders()
            val matches = messages.filter { it.subject == subject }
            check(matches.size == 1) {
                "Expected one message with subject '$subject' in $folder on the server, found ${matches.size}"
            }
            block(session, delimiter, matches.single().uid)
        }
    }
}
