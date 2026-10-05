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
@Suppress("TooManyFunctions")
interface ScenarioDriver : AutoCloseable {
    /** Sets up an account the way account setup does, including whatever the app does right after setup. */
    fun addAccount(spec: AccountSpec): ClientAccount

    /**
     * The user changes account settings (the account settings screen). Only the non-null values of [settings] change.
     */
    fun changeSettings(account: ClientAccount, settings: ClientAccountSettings)

    /**
     * Chooses which buttons new-mail notifications show, in this order ("Notifications" settings, "Message actions").
     * Buttons for actions an account can't do (e.g. archive without an archive folder) are left out.
     */
    fun setNotificationActions(actions: List<NotificationButton>)

    /**
     * "Confirm actions: delete (from notifications)" in the general settings. On by default, so a notification's
     * Delete button asks for confirmation in a separate screen instead of deleting.
     */
    fun setConfirmDeleteFromNotification(confirm: Boolean)

    /** The user removes the account (account settings, "Remove account"). */
    fun removeAccount(account: ClientAccount)

    /** The user pulls to refresh while looking at [folder] in the message list. */
    fun pullToRefresh(account: ClientAccount, folder: FolderPath)

    /**
     * Like [pullToRefresh], but returns as soon as the refresh has started, so the scenario can act while it runs.
     * [awaitIdle] waits for it to finish.
     */
    fun startPullToRefresh(account: ClientAccount, folder: FolderPath)

    /** "Sync all accounts" in the navigation drawer: every account checks for new mail, ignoring check intervals. */
    fun syncAllAccounts()

    /**
     * The user turns on push for [folder] in its folder settings while the app is open. The app then keeps a
     * connection open to be told about new mail; [ScenarioScope.awaitAppListening] waits until it is.
     */
    fun enablePush(account: ClientAccount, folder: FolderPath)

    /** Shows or hides [folder] in the folder list (folder settings). Hidden folders aren't synced in the background. */
    fun setFolderVisible(account: ClientAccount, folder: FolderPath, visible: Boolean)

    /** Turns background sync of [folder] on or off (folder settings, "Sync folder"). */
    fun setFolderSyncEnabled(account: ClientAccount, folder: FolderPath, enabled: Boolean)

    /** "Clear local messages" in the folder settings: removes the app's copies, not the server's. */
    fun clearLocalMessages(account: ClientAccount, folder: FolderPath)

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

    /** Copies the message to [to], chosen in the folder picker. */
    fun copy(account: ClientAccount, folder: FolderPath, subject: String, to: FolderPath)

    /** The "Spam" action, without a confirmation dialog: moves the message to the account's spam folder. */
    fun markAsSpam(account: ClientAccount, folder: FolderPath, subject: String)

    /** "Move to Drafts": the message becomes a draft the user can edit. */
    fun moveToDrafts(account: ClientAccount, folder: FolderPath, subject: String)

    /**
     * Selects several messages, possibly from different accounts (e.g. in the unified inbox), and applies one action
     * to all of them at once.
     */
    fun actOnSelection(messages: List<MessageSelector>, action: SelectionAction)

    /** "Mark all as read" in the message list of [folder]. */
    fun markAllRead(account: ClientAccount, folder: FolderPath)

    /** "Empty trash" in the message list of the account's trash folder; fails if the account has none. */
    fun emptyTrash(account: ClientAccount)

    /** "Empty spam" in the message list of the account's spam folder; fails if the account has none. */
    fun emptySpam(account: ClientAccount)

    /** "Expunge" in the message list of [folder]: removes messages marked as deleted from the server. */
    fun expunge(account: ClientAccount, folder: FolderPath)

    /**
     * Taps "load more messages" at the end of the message list of [folder]; fails if the app doesn't show it, i.e.
     * it has no older messages to fetch.
     */
    fun loadMore(account: ClientAccount, folder: FolderPath)

    // Thread actions act on the thread shown with the given subject in the threaded message list of the folder (the
    // list shows a thread under the subject of its newest message).

    /** The threads the user sees in [folder] when the message list groups messages by conversation, newest first. */
    fun threadList(account: ClientAccount, folder: FolderPath): List<ClientThread>

    fun deleteThread(account: ClientAccount, folder: FolderPath, subject: String)

    fun archiveThread(account: ClientAccount, folder: FolderPath, subject: String)

    fun moveThread(account: ClientAccount, folder: FolderPath, subject: String, to: FolderPath)

    fun copyThread(account: ClientAccount, folder: FolderPath, subject: String, to: FolderPath)

    fun setThreadRead(account: ClientAccount, folder: FolderPath, subject: String, read: Boolean)

    fun setThreadStarred(account: ClientAccount, folder: FolderPath, subject: String, starred: Boolean)

    // Reading messages

    /**
     * Opens the message from the message list and returns what the message view shows once it has loaded. Opening
     * downloads what's missing to display it, and may mark the message as read (account setting).
     */
    fun open(account: ClientAccount, folder: FolderPath, subject: String): ClientMessageContent

    /** "Download complete message" in the message view of a partially downloaded message. */
    fun downloadCompleteMessage(account: ClientAccount, folder: FolderPath, subject: String): ClientMessageContent

    /**
     * Opens the message and taps the attachment named [fileName] to download it. Returns its content, or null if the
     * app reported that the download failed.
     */
    fun downloadAttachment(account: ClientAccount, folder: FolderPath, subject: String, fileName: String): ByteArray?

    /**
     * The user searches [folder] for [query] and then taps "search on server". Returns the result list once the
     * server search has finished.
     */
    fun searchOnServer(account: ClientAccount, folder: FolderPath, query: String): ClientRemoteSearch

    /** Taps "load more results" at the end of a server search result list. */
    fun loadMoreSearchResults(search: ClientRemoteSearch): ClientRemoteSearch

    // Composing

    /** Writes a new message and taps "Send". */
    fun send(account: ClientAccount, composition: Composition)

    /** Writes a new message and saves it as a draft. */
    fun saveDraft(account: ClientAccount, composition: Composition)

    /** Opens the draft with [subject] from the drafts folder, replaces its content with [composition] and saves it. */
    fun editDraft(account: ClientAccount, subject: String, composition: Composition)

    /** Opens the draft with [subject] from the drafts folder and sends it unchanged. */
    fun sendDraft(account: ClientAccount, subject: String)

    /** Opens the draft with [subject] from the drafts folder and discards it. */
    fun discardDraft(account: ClientAccount, subject: String)

    /** The refresh action in "Manage folders": the app fetches the server's folder list. */
    fun refreshFolders(account: ClientAccount)

    /** The user enters a new password for the incoming server in the account's server settings and saves. */
    fun updatePassword(account: ClientAccount, password: String)

    /** The user enters a new password for the outgoing (SMTP) server in the account's server settings and saves. */
    fun updateOutgoingPassword(account: ClientAccount, password: String)

    // What the user sees

    /** The folders the user sees for [account], including local-only folders such as the outbox. */
    fun folderList(account: ClientAccount): List<ClientFolder>

    /** The messages the user sees in [folder], newest first, one entry per message (no threading). */
    fun messageList(account: ClientAccount, folder: FolderPath): List<ClientMessage>

    /** The messages waiting in the app's own outbox, newest first. */
    fun outbox(account: ClientAccount): List<ClientMessage>

    /** The unified inbox: the inboxes of all accounts, newest first, one entry per message. */
    fun unifiedInbox(): List<ClientAccountMessage>
}

enum class MailProtocol { IMAP, POP3 }

/**
 * Plaintext account settings. [checkIntervalMinutes] null means the account never syncs in the background; otherwise
 * the app schedules periodic sync, which runs as [ScenarioDevice.advanceTime] lets time pass. [notifyNewMail] is the
 * "notify me about new mail" choice of account setup.
 */
data class AccountSpec(
    val email: String,
    val protocol: MailProtocol,
    val incomingHost: String,
    val incomingPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val username: String,
    val password: String,
    val smtpPassword: String,
    val checkIntervalMinutes: Int? = null,
    val notifyNewMail: Boolean = false,
) {
    override fun toString() =
        "AccountSpec(email=$email, $protocol=$incomingHost:$incomingPort, smtp=$smtpHost:$smtpPort, username=$username)"
}

/**
 * Account settings a user can change in the account settings screen. Null means "leave as is"; new accounts start
 * with the app's defaults.
 */
data class ClientAccountSettings(
    val deleteFromServer: DeleteFromServer? = null,
    val markReadOnDelete: Boolean? = null,
    val expunge: ExpungeMode? = null,
    val markReadOnOpen: Boolean? = null,
    /** "Automatically download messages up to": larger messages are only partially downloaded. */
    val autoDownloadLimitBytes: Int? = null,
    val uploadSentMessages: Boolean? = null,
    val showSyncNotifications: Boolean? = null,
    val remoteSearchResultLimit: Int? = null,
    val notifyNewMail: Boolean? = null,
)

/** A button new-mail notifications can show, see [ScenarioDriver.setNotificationActions]. */
enum class NotificationButton { REPLY, MARK_READ, DELETE, STAR, ARCHIVE, SPAM }

/** "When I delete a message": what deleting does on the server. */
enum class DeleteFromServer { NEVER, ON_DELETE, MARK_AS_READ }

/** "Erase deleted messages on server". */
enum class ExpungeMode { IMMEDIATELY, ON_POLL, MANUALLY }

/** A message picked for a batch action in [ScenarioDriver.actOnSelection]. */
data class MessageSelector(val account: ClientAccount, val folder: FolderPath, val subject: String)

enum class SelectionAction { MARK_READ, MARK_UNREAD, STAR, UNSTAR, DELETE, ARCHIVE }

/** A message the user writes. Recipients are email addresses. */
data class Composition(
    val to: List<String>,
    val subject: String,
    val text: String,
)

/** The subjects of the messages the user sees in [folder], newest first. */
fun ScenarioDriver.subjects(account: ClientAccount, folder: FolderPath = FolderPath.INBOX): List<String?> =
    messageList(account, folder).map(ClientMessage::subject)

/** The one message with [subject] the user sees in [folder]. */
fun ScenarioDriver.message(
    account: ClientAccount,
    subject: String,
    folder: FolderPath = FolderPath.INBOX,
): ClientMessage =
    messageList(account, folder).single { it.subject == subject }

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

/** An entry of a list that mixes accounts, such as the unified inbox. */
data class ClientAccountMessage(
    val accountEmail: String,
    val message: ClientMessage,
)

/** A conversation in the threaded message list: shown with its newest message's [subject]. */
data class ClientThread(
    val subject: String?,
    val messageCount: Int,
    val isRead: Boolean,
    val isStarred: Boolean,
)

/** What the message view shows. [isComplete] is false while the app only has part of the message. */
data class ClientMessageContent(
    val subject: String?,
    val text: String?,
    val isComplete: Boolean,
    val attachments: List<ClientAttachment>,
)

data class ClientAttachment(val fileName: String, val size: Long, val isDownloaded: Boolean)

/**
 * The result list of a server search. [hasMoreResults] is true when the server found more than the app fetched and
 * the list offers "load more results"; [failed] is true when the app reported that the search failed.
 */
class ClientRemoteSearch internal constructor(
    val account: ClientAccount,
    val folder: FolderPath,
    val query: String,
    val results: List<ClientMessage>,
    val hasMoreResults: Boolean,
    val failed: Boolean,
    internal val state: Any?,
)
