package net.thunderbird.android.scenario.harness

import app.k9mail.feature.account.common.AccountCommonExternalContract.AccountStateLoader
import app.k9mail.feature.account.common.domain.entity.Account
import app.k9mail.feature.account.common.domain.entity.AccountOptions
import app.k9mail.feature.account.edit.AccountEditExternalContract.AccountServerSettingsUpdater
import app.k9mail.feature.account.edit.AccountEditExternalContract.AccountUpdaterResult
import app.k9mail.feature.account.setup.AccountSetupExternalContract.AccountCreator
import app.k9mail.feature.account.setup.AccountSetupExternalContract.AccountCreator.AccountCreatorResult
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import app.k9mail.legacy.ui.folder.DisplayFolder
import app.k9mail.legacy.ui.folder.DisplayFolderRepository
import com.fsck.k9.Preferences
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.controller.MessagingControllerWrapper
import com.fsck.k9.controller.push.PushController
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.store.imap.ImapStoreSettings
import com.fsck.k9.ui.messagelist.MessageListConfig
import com.fsck.k9.ui.messagelist.MessageListInfo
import com.fsck.k9.ui.messagelist.MessageListItem
import com.fsck.k9.ui.messagelist.MessageListLoader
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.SortType
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.folder.api.FolderType
import net.thunderbird.feature.mail.folder.api.data.repository.FolderDetailsRepository
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.mail.testserver.fixture.FolderPath
import org.koin.core.Koin

/**
 * [ScenarioDriver] for the current app, built on the legacy `MessagingController` sync core.
 *
 * Actions make the same calls as the UI:
 * - account setup goes through the app's [AccountCreator], the last step of the setup wizard,
 * - pull to refresh and the message actions (flags, delete, archive, move, mark all read, empty trash, load more) go
 *   through [MessagingControllerWrapper] like `LegacyMessageListFragment`, the message list shown while the
 *   `enable_message_list_new_state` feature flag is off (the default), in its unthreaded mode,
 * - refreshing folders calls [MessagingController.refreshFolderList] like `ManageFoldersFragment`,
 * - changing the password goes through [AccountServerSettingsUpdater] like the "save" step of the incoming server
 *   settings screen,
 * - the folder list comes from [DisplayFolderRepository], like the folder drawer,
 * - the message list comes from [MessageListLoader], like the message list screen (unthreaded, by date).
 *
 * Special folders are set up as a new account gets them: account setup leaves every special folder on automatic
 * selection, and the folder list refresh at the end of setup picks the folders the server marks with SPECIAL-USE
 * attributes (`\Trash`, `\Archive`, ...), see `DefaultSpecialFolderUpdater`. Apache James creates Trash, Sent, Drafts
 * and Spam for new users; an archive folder exists only if the scenario seeds one, e.g.
 * `folder("Archive", specialUse = SpecialUse.ARCHIVE)`. The same refresh enables notifications for the inbox, so
 * [AccountSpec.notifyNewMail] is all it takes for new inbox mail to notify the user.
 *
 * After every action the driver waits until the controller has run all follow-up work, see
 * [MessagingControllerQueue].
 */
internal class LegacyScenarioDriver(
    koin: Koin,
    timeout: Duration = DEFAULT_TIMEOUT,
) : ScenarioDriver {
    private val accountCreator: AccountCreator = koin.get()
    private val preferences: Preferences = koin.get()
    private val messagingController: MessagingController = koin.get()
    private val messagingControllerWrapper: MessagingControllerWrapper = koin.get()
    private val displayFolderRepository: DisplayFolderRepository = koin.get()
    private val messageListLoader: MessageListLoader = koin.get()

    // The message list passes its own listener for progress updates; scenarios read results from the UI's data sources.
    private val uiListener = object : SimpleMessagingListener() {}

    private val pump = MainLooperPump(timeout)
    private val controllerQueue = MessagingControllerQueue(messagingController)

    private val folderDetailsRepository: FolderDetailsRepository = koin.get()
    private val pushController: PushController = koin.get()
    private val accountStateLoader: AccountStateLoader = koin.get()
    private val serverSettingsUpdater: AccountServerSettingsUpdater = koin.get()

    override fun addAccount(spec: AccountSpec): ClientAccount {
        val account = Account(
            uuid = UUID.randomUUID().toString(),
            emailAddress = spec.email,
            incomingServerSettings = ServerSettings(
                type = "imap",
                host = spec.imapHost,
                port = spec.imapPort,
                connectionSecurity = ConnectionSecurity.NONE,
                authenticationType = AuthType.PLAIN,
                username = spec.username,
                password = spec.password,
                clientCertificateAlias = null,
                extra = ImapStoreSettings.createExtra(
                    autoDetectNamespace = true,
                    pathPrefix = null,
                    // Compressed traffic would make the proxy transcript unreadable.
                    useCompression = false,
                    sendClientInfo = true,
                ),
            ),
            outgoingServerSettings = ServerSettings(
                type = "smtp",
                host = spec.imapHost,
                port = UNUSED_SMTP_PORT,
                connectionSecurity = ConnectionSecurity.NONE,
                authenticationType = AuthType.PLAIN,
                username = spec.username,
                password = spec.password,
                clientCertificateAlias = null,
            ),
            authorizationState = null,
            specialFolderSettings = null,
            options = AccountOptions(
                accountName = spec.email,
                displayName = "Scenario User",
                emailSignature = null,
                // Without an interval there's no periodic sync and the app runs one mail check right after setup
                // instead; addAccount waits for it. With an interval, the app schedules a periodic WorkManager job,
                // which runs when the scenario's device settles (see ScenarioDevice).
                checkFrequencyInMinutes = spec.checkIntervalMinutes ?: LegacyAccountDto.INTERVAL_MINUTES_NEVER,
                messageDisplayCount = MESSAGE_DISPLAY_COUNT,
                showNotification = spec.notifyNewMail,
            ),
        )

        val result = pump.runInBackground("account creation") {
            runBlocking { accountCreator.createAccount(account) }
        }
        check(result is AccountCreatorResult.Success) { "Account setup failed: $result" }
        awaitIdle()

        return ClientAccount(id = result.accountUuid, email = spec.email)
    }

    override fun pullToRefresh(account: ClientAccount, folder: FolderPath) {
        val accountDto = accountDto(account)
        val folderId = folderId(accountDto, folder)

        // Same calls as LegacyMessageListFragment.checkMail() when it shows a single folder of a single account.
        messagingControllerWrapper.synchronizeMailbox(accountDto.id, folderId, false, uiListener)
        messagingControllerWrapper.sendPendingMessages(accountDto.id, uiListener)
        awaitIdle()
    }

    override fun enablePush(account: ClientAccount, folder: FolderPath) {
        val accountDto = accountDto(account)
        val folderId = folderId(accountDto, folder)

        // Same as the "Push" switch in the folder's settings (FolderSettingsDataStore).
        pump.runInBackground("enabling push for $folder") {
            runBlocking {
                val details = folderDetailsRepository.findById(accountDto.id, folderId).fold(
                    onSuccess = { it ?: error("Folder $folder not found") },
                    onFailure = { error("Couldn't read the settings of $folder: $it") },
                )
                folderDetailsRepository.update(accountDto.id, details.copy(isPushEnabled = true)).fold(
                    onSuccess = {},
                    onFailure = { error("Couldn't enable push for $folder: $it") },
                )
            }
        }

        // Every activity does this when it's created (BaseActivity), i.e. the app is open.
        pushController.init()
        awaitIdle()
    }

    override fun markRead(account: ClientAccount, folder: FolderPath, subject: String) {
        setFlag(account, folder, subject, Flag.SEEN, true)
    }

    override fun markUnread(account: ClientAccount, folder: FolderPath, subject: String) {
        setFlag(account, folder, subject, Flag.SEEN, false)
    }

    override fun setStarred(account: ClientAccount, folder: FolderPath, subject: String, starred: Boolean) {
        setFlag(account, folder, subject, Flag.FLAGGED, starred)
    }

    /** Same call as LegacyMessageListFragment.setFlag() for a message that isn't shown as a thread. */
    private fun setFlag(account: ClientAccount, folder: FolderPath, subject: String, flag: Flag, newState: Boolean) {
        val item = messageListItem(account, folder, subject)
        messagingControllerWrapper.setFlag(item.account.id, listOf(item.databaseId), flag, newState)
        awaitIdle()
    }

    override fun delete(account: ClientAccount, folder: FolderPath, subject: String) {
        val item = messageListItem(account, folder, subject)

        // Same as LegacyMessageListFragment.onDeleteConfirmed() in the unthreaded list; the swipe action and the menu
        // both end up there, and confirming deletes is off by default.
        messagingControllerWrapper.deleteMessages(listOf(item.messageReference))
        awaitIdle()
    }

    override fun archive(account: ClientAccount, folder: FolderPath, subject: String) {
        val item = messageListItem(account, folder, subject)
        checkNotNull(item.account.archiveFolderId) {
            "${account.email} has no archive folder; the app would offer to set one up instead of archiving"
        }
        checkMovePossible(item)

        // Same as LegacyMessageListFragment.onArchive() in the unthreaded list.
        messagingControllerWrapper.archiveMessages(listOf(item.messageReference))
        awaitIdle()
    }

    override fun move(account: ClientAccount, folder: FolderPath, subject: String, to: FolderPath) {
        val item = messageListItem(account, folder, subject)
        val destinationFolderId = folderId(accountDto(account), to)
        checkMovePossible(item)
        require(destinationFolderId != item.folderId) { "The message is already in $to" }

        // Same as LegacyMessageListFragment.copyOrMove() for a move in the unthreaded list, after the user picked the
        // destination in the folder picker.
        messagingControllerWrapper.moveMessages(
            item.account.id,
            item.folderId,
            listOf(item.messageReference),
            destinationFolderId,
        )
        awaitIdle()
    }

    /** The checks of LegacyMessageListFragment.checkCopyOrMovePossible(), which shows a toast instead of moving. */
    private fun checkMovePossible(item: MessageListItem) {
        check(messagingControllerWrapper.isMoveCapable(item.account.id)) { "The account can't move messages" }
        check(messagingControllerWrapper.isMoveCapable(item.messageReference)) {
            "The message '${item.subject}' can't be moved yet (not synced)"
        }
    }

    override fun markAllRead(account: ClientAccount, folder: FolderPath) {
        val accountDto = accountDto(account)
        val displayFolder = displayFolder(accountDto, folder)
        check(displayFolder.folder.type != FolderType.OUTBOX) { "The outbox has no \"Mark all as read\"" }
        val folderId = displayFolder.folder.id

        // Same as LegacyMessageListFragment.markAllAsRead() when showing one folder of one account.
        messagingControllerWrapper.markAllMessagesRead(accountDto.id, folderId)
        awaitIdle()
    }

    override fun emptyTrash(account: ClientAccount) {
        val accountDto = accountDto(account)
        checkNotNull(accountDto.trashFolderId) { "${account.email} has no trash folder, so there's no \"Empty trash\"" }

        // Same as confirming the "Empty trash" dialog in LegacyMessageListFragment.
        messagingControllerWrapper.emptyTrash(accountDto.id)
        awaitIdle()
    }

    override fun loadMore(account: ClientAccount, folder: FolderPath) {
        val accountDto = accountDto(account)
        val folderId = folderId(accountDto, folder)
        check(messageListInfo(account, folder).hasMoreMessages) {
            "The message list of $folder doesn't offer to load more messages"
        }

        // Same as LegacyMessageListFragment.onFooterClicked() for a folder with more messages on the server.
        messagingControllerWrapper.loadMoreMessages(accountDto.id, folderId)
        awaitIdle()
    }

    override fun refreshFolders(account: ClientAccount) {
        // Same as the refresh action of ManageFoldersFragment.
        messagingController.refreshFolderList(accountDto(account))
        awaitIdle()
    }

    override fun updatePassword(account: ClientAccount, password: String) {
        // Same as the incoming server settings screen: it loads the account's settings (LoadAccountState), the user
        // edits the password, and the save step (SaveServerSettings) hands the settings to the updater. The
        // connection check the screen makes before saving isn't repeated here.
        pump.runInBackground("saving the incoming server password") {
            runBlocking {
                val state = accountStateLoader.loadAccountState(account.id)
                    ?: error("Account ${account.email} not found in the app")
                val incoming = checkNotNull(state.incomingServerSettings) { "Account without incoming settings" }
                val result = serverSettingsUpdater.updateServerSettings(
                    accountUuid = account.id,
                    isIncoming = true,
                    serverSettings = incoming.copy(password = password),
                    authorizationState = state.authorizationState,
                )
                check(result is AccountUpdaterResult.Success) { "Saving the server settings failed: $result" }
            }
        }
        awaitIdle()
    }

    override fun folderList(account: ClientAccount): List<ClientFolder> {
        return displayFolders(accountDto(account)).map { displayFolder ->
            ClientFolder(
                path = displayFolder.toFolderPath(),
                unreadCount = displayFolder.unreadMessageCount,
                isLocalOnly = displayFolder.folder.isLocalOnly,
            )
        }
    }

    override fun messageList(account: ClientAccount, folder: FolderPath): List<ClientMessage> {
        return messageListItems(account, folder).map { item ->
            ClientMessage(
                subject = item.subject,
                senderAddress = item.displayAddress?.address,
                senderName = item.displayName.toString(),
                isRead = item.isRead,
                isStarred = item.isStarred,
            )
        }
    }

    override fun close() {
        try {
            controllerQueue.stopController()
        } finally {
            pump.close()
        }
    }

    private fun messageListItem(account: ClientAccount, folder: FolderPath, subject: String): MessageListItem {
        val matches = messageListItems(account, folder).filter { it.subject == subject }
        return matches.singleOrNull()
            ?: error("Expected one message with subject '$subject' in $folder, found ${matches.size}")
    }

    private fun messageListItems(account: ClientAccount, folder: FolderPath): List<MessageListItem> =
        messageListInfo(account, folder).messageListItems

    private fun messageListInfo(account: ClientAccount, folder: FolderPath): MessageListInfo {
        val accountDto = accountDto(account)
        val search = LocalMessageSearch().apply {
            addAccountUuid(accountDto.uuid)
            addAllowedFolder(folderId(accountDto, folder))
        }
        val config = MessageListConfig(
            search = search,
            showingThreadedList = false,
            sortType = SortType.SORT_DATE,
            sortAscending = false,
            sortDateAscending = false,
            activeMessage = null,
            sortOverrides = emptyMap(),
        )

        // Note: the loader logs and swallows errors, returning an empty list, just like the UI shows one.
        return pump.runInBackground("message list of $folder") {
            messageListLoader.getMessageList(config)
        }
    }

    private fun displayFolders(accountDto: LegacyAccountDto): List<DisplayFolder> {
        return pump.runInBackground("folder list") {
            runBlocking {
                displayFolderRepository.getDisplayFoldersFlow(accountDto, includeHiddenFolders = false).first()
            }
        }
    }

    private fun folderId(accountDto: LegacyAccountDto, path: FolderPath): Long = displayFolder(
        accountDto,
        path,
    ).folder.id

    private fun displayFolder(accountDto: LegacyAccountDto, path: FolderPath): DisplayFolder {
        val folders = displayFolders(accountDto)
        return folders.firstOrNull { it.toFolderPath() == path }
            ?: error("Folder $path not in the app's folder list: ${folders.map { it.toFolderPath() }}")
    }

    private fun accountDto(account: ClientAccount): LegacyAccountDto {
        return preferences.getAccount(account.id) ?: error("Account ${account.email} not found in the app")
    }

    override fun awaitIdle() {
        controllerQueue.awaitIdle(pump)
    }

    /** Maps the name shown in the app back to a logical path, using the server's hierarchy delimiter. */
    private fun DisplayFolder.toFolderPath(): FolderPath {
        if (folder.type == FolderType.INBOX) return FolderPath.INBOX

        val segments = if (pathDelimiter.isEmpty()) listOf(folder.name) else folder.name.split(pathDelimiter)
        return FolderPath(segments)
    }

    private companion object {
        val DEFAULT_TIMEOUT = 2.minutes

        // SMTP isn't used by scenarios; nothing listens here.
        const val UNUSED_SMTP_PORT = 1
        const val MESSAGE_DISPLAY_COUNT = 25
    }
}
