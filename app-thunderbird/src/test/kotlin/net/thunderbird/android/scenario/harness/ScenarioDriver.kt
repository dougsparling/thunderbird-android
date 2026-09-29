package net.thunderbird.android.scenario.harness

import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * What a user can do with the app and what they can see, in domain terms.
 *
 * Scenarios talk to the app only through this interface, so they keep working when the sync core behind it is
 * replaced: a new implementation drives the new code, the scenarios stay the same. Every action returns once the app
 * has finished the work it started, so scenarios never need to wait or poll.
 *
 * Actions are named after what the user does, never after how the app does the work, so any sync core can implement
 * them. What the Android platform does (permissions, time passing, background work, notifications) is the same for
 * any implementation and lives in [ScenarioDevice] instead.
 */
interface ScenarioDriver : AutoCloseable {
    /** Sets up an IMAP account the way account setup does, including whatever the app does right after setup. */
    fun addAccount(spec: AccountSpec): ClientAccount

    /** The user pulls to refresh while looking at [folder] in the message list. */
    fun pullToRefresh(account: ClientAccount, folder: FolderPath)

    /**
     * The user turns on push for [folder] in its folder settings while the app is open. The app then keeps a
     * connection open to be told about new mail; [ScenarioScope.awaitAppListening] waits until it is.
     */
    fun enablePush(account: ClientAccount, folder: FolderPath)

    /**
     * Waits until the app has finished all work it has started so far. Actions already do this before returning;
     * [ScenarioDevice.settle] uses it to let the app and the device run until both are done.
     */
    fun awaitIdle()

    // Message actions act on the single message with the given subject in the folder, the way a user does from the
    // message list (swipe action, context menu or selection). They fail if the subject doesn't identify one message.

    /** Marks the message as read. */
    fun markRead(account: ClientAccount, folder: FolderPath, subject: String)

    /** Marks the message as unread. */
    fun markUnread(account: ClientAccount, folder: FolderPath, subject: String)

    /** Stars ([starred] true) or unstars the message. */
    fun setStarred(account: ClientAccount, folder: FolderPath, subject: String, starred: Boolean)

    /** Deletes the message, without a confirmation dialog. Where it goes depends on the account's trash folder. */
    fun delete(account: ClientAccount, folder: FolderPath, subject: String)

    /** Archives the message; fails if the account has no archive folder (the app would offer to set one up). */
    fun archive(account: ClientAccount, folder: FolderPath, subject: String)

    /** Moves the message to [to], chosen in the folder picker. */
    fun move(account: ClientAccount, folder: FolderPath, subject: String, to: FolderPath)

    /** "Mark all as read" in the message list of [folder]. */
    fun markAllRead(account: ClientAccount, folder: FolderPath)

    /** "Empty trash" in the message list of the account's trash folder; fails if the account has none. */
    fun emptyTrash(account: ClientAccount)

    /**
     * Taps "load more messages" at the end of the message list of [folder]; fails if the app doesn't show it, i.e.
     * it has no older messages to fetch.
     */
    fun loadMore(account: ClientAccount, folder: FolderPath)

    /** The refresh action in "Manage folders": the app fetches the server's folder list. */
    fun refreshFolders(account: ClientAccount)

    /** The user enters a new password for the incoming (IMAP) server in the account's server settings and saves. */
    fun updatePassword(account: ClientAccount, password: String)

    /** The folders the user sees for [account], including local-only folders such as the outbox. */
    fun folderList(account: ClientAccount): List<ClientFolder>

    /** The messages the user sees in [folder], newest first, one entry per message (no threading). */
    fun messageList(account: ClientAccount, folder: FolderPath): List<ClientMessage>
}

/**
 * Plaintext IMAP account settings. SMTP is not used by scenarios. [checkIntervalMinutes] null means the account never
 * syncs in the background; otherwise the app schedules periodic sync, which runs as [ScenarioDevice.advanceTime] lets
 * time pass. [notifyNewMail] is the "notify me about new mail" choice of account setup.
 */
data class AccountSpec(
    val email: String,
    val imapHost: String,
    val imapPort: Int,
    val username: String,
    val password: String,
    val checkIntervalMinutes: Int? = null,
    val notifyNewMail: Boolean = false,
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
