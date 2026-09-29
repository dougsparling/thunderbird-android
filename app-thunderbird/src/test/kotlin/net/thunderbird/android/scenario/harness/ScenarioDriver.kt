package net.thunderbird.android.scenario.harness

import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * What a user can do with the app and what they can see, in domain terms.
 *
 * Scenarios talk to the app only through this interface, so they keep working when the sync core behind it is
 * replaced: a new implementation drives the new code, the scenarios stay the same. Every action returns once the app
 * has finished the work it started, so scenarios never need to wait or poll.
 */
interface ScenarioDriver : AutoCloseable {
    /** Sets up an IMAP account the way account setup does, including whatever the app does right after setup. */
    fun addAccount(spec: AccountSpec): ClientAccount

    /** Fetches the folder list from the server. */
    fun refreshFolders(account: ClientAccount)

    /** Synchronizes one folder with the server, including sending pending local changes first. */
    fun sync(account: ClientAccount, folder: FolderPath)

    /** Marks the single message with [subject] in [folder] as read, like a user does from the message list. */
    fun markRead(account: ClientAccount, folder: FolderPath, subject: String)

    /** The folders the user sees for [account], including local-only folders such as the outbox. */
    fun folderList(account: ClientAccount): List<ClientFolder>

    /** The messages the user sees in [folder], newest first, one entry per message (no threading). */
    fun messageList(account: ClientAccount, folder: FolderPath): List<ClientMessage>
}

/** Plaintext IMAP account settings. SMTP is not used by scenarios. */
data class AccountSpec(
    val email: String,
    val imapHost: String,
    val imapPort: Int,
    val username: String,
    val password: String,
) {
    override fun toString() = "AccountSpec(email=$email, imap=$imapHost:$imapPort, username=$username)"
}

/** Handle for an account in the app. */
data class ClientAccount(val id: String, val email: String)

data class ClientFolder(
    val path: FolderPath,
    val unreadCount: Int,
    val isLocalOnly: Boolean,
)

data class ClientMessage(
    val subject: String?,
    val senderAddress: String?,
    val senderName: String,
    val isRead: Boolean,
    val isStarred: Boolean,
)
