package net.thunderbird.android.scenario.harness

import android.text.Html
import app.k9mail.feature.account.common.AccountCommonExternalContract.AccountStateLoader
import app.k9mail.feature.account.common.domain.entity.Account
import app.k9mail.feature.account.common.domain.entity.AccountOptions
import app.k9mail.feature.account.edit.AccountEditExternalContract.AccountServerSettingsUpdater
import app.k9mail.feature.account.edit.AccountEditExternalContract.AccountUpdaterResult
import app.k9mail.feature.account.setup.AccountSetupExternalContract.AccountCreator
import app.k9mail.feature.account.setup.AccountSetupExternalContract.AccountCreator.AccountCreatorResult
import app.k9mail.legacy.message.controller.MessageReference
import app.k9mail.legacy.ui.folder.DisplayFolder
import app.k9mail.legacy.ui.folder.DisplayFolderRepository
import com.fsck.k9.Preferences
import com.fsck.k9.activity.MessageBodyDownloader
import com.fsck.k9.activity.compose.MessageComposeOperations
import com.fsck.k9.controller.push.PushController
import com.fsck.k9.mail.Address
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Message.RecipientType
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeUtility
import com.fsck.k9.mail.store.imap.ImapStoreSettings
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.mailstore.LocalMessageReader
import com.fsck.k9.mailstore.MessageViewInfo
import com.fsck.k9.mailstore.MessageViewInfoExtractorFactory
import com.fsck.k9.message.MessageBuilder
import com.fsck.k9.message.QuotedTextMode
import com.fsck.k9.message.SimpleMessageBuilder
import com.fsck.k9.message.SimpleMessageFormat
import com.fsck.k9.ui.helper.launchUserChange
import com.fsck.k9.ui.messagelist.MessageListConfig
import com.fsck.k9.ui.messagelist.MessageListInfo
import com.fsck.k9.ui.messagelist.MessageListItem
import com.fsck.k9.ui.messagelist.MessageListLoader
import com.fsck.k9.ui.messageview.AttachmentLoadingController
import com.fsck.k9.ui.settings.account.AccountSettingsDataStoreFactory
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.core.android.account.DeletePolicy
import net.thunderbird.core.android.account.Expunge
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.QuoteStyle
import net.thunderbird.core.android.account.SortType
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.common.notification.NotificationActionTokens
import net.thunderbird.core.preference.interaction.InteractionSettingsPreferenceManager
import net.thunderbird.core.preference.notification.NotificationPreferenceManager
import net.thunderbird.feature.account.settings.api.BackgroundAccountRemover
import net.thunderbird.feature.mail.folder.FolderType
import net.thunderbird.feature.mail.folder.api.FolderDetails
import net.thunderbird.feature.mail.folder.api.OutboxFolderManager
import net.thunderbird.feature.mail.folder.api.data.repository.FolderDetailsRepository
import net.thunderbird.feature.mail.folder.api.getOutboxFolderIdSync
import net.thunderbird.feature.mail.message.reader.api.html.MessageReaderHtmlSettingsProvider
import net.thunderbird.feature.mail.sync.api.MailSynchronizer
import net.thunderbird.feature.mail.sync.api.MessageCapabilities
import net.thunderbird.feature.mail.sync.api.MessageDeleteRepository
import net.thunderbird.feature.mail.sync.api.MessageFlagRepository
import net.thunderbird.feature.mail.sync.api.MessageMoveRepository
import net.thunderbird.feature.mail.sync.api.NewMailNotifications
import net.thunderbird.feature.mail.sync.api.OutboxSender
import net.thunderbird.feature.mail.sync.api.RemoteContentRepository
import net.thunderbird.feature.mail.sync.api.RemoteSearchEvent
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.SearchAccount
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.api.SearchCondition
import net.thunderbird.mail.testserver.fixture.FolderPath
import org.koin.core.Koin
import org.koin.core.qualifier.named

/**
 * [ScenarioDriver] for the current app.
 *
 * Actions make the same calls as the UI:
 * - account setup goes through the app's [AccountCreator], the last step of the setup wizard; settings changes go
 *   through the account settings screen's data store, account removal through [BackgroundAccountRemover],
 * - pull to refresh and the message actions (flags, delete, archive, move, copy, spam, mark all read, empty trash and
 *   spam, expunge, load more, remote search, thread actions) make the same calls as
 *   `LegacyMessageListFragment`, the message list shown while the `enable_message_list_new_state` feature flag is off
 *   (the default); unthreaded unless an action says it acts on a thread,
 * - opening a message, downloading its body and its attachments follow `MessageViewFragment`,
 *   `MessageLoaderHelper` and `AttachmentController`,
 * - sending and drafts follow `MessageCompose` (its `SendMessageTask` and `SaveMessageTask`), with the message built
 *   by the same [SimpleMessageBuilder],
 * - refreshing folders calls [MailSynchronizer.requestFolderListRefresh] like `ManageFoldersFragment`; folder settings
 *   follow `FolderSettingsDataStore` and `FolderSettingsViewModel`,
 * - changing a password goes through [AccountServerSettingsUpdater] like the "save" step of the server settings
 *   screens,
 * - "sync all accounts" calls [MailSynchronizer.checkMail] like the drawer's `SyncAllAccounts`,
 * - the folder list comes from [DisplayFolderRepository], like the folder drawer,
 * - message lists come from [MessageListLoader], like the message list screen (by date, newest first).
 *
 * Special folders are set up as a new account gets them: account setup leaves every special folder on automatic
 * selection, and the folder list refresh at the end of setup picks the folders the server marks with SPECIAL-USE
 * attributes (`\Trash`, `\Archive`, ...), see `DefaultSpecialFolderUpdater`. Apache James creates Trash, Sent, Drafts
 * and Spam for new users; an archive folder exists only if the scenario seeds one, e.g.
 * `folder("Archive", specialUse = SpecialUse.ARCHIVE)`. The same refresh enables notifications for the inbox, so
 * [AccountSpec.notifyNewMail] is all it takes for new inbox mail to notify the user.
 *
 * Changes the UI starts with `launchUserChange` are started the same way, on the main thread.
 *
 * After every action the driver waits until the app has run all follow-up work, see [RemoteWorkQueue].
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class LegacyScenarioDriver(
    private val koin: Koin,
    timeout: Duration = DEFAULT_TIMEOUT,
) : ScenarioDriver {
    private val accountCreator: AccountCreator = koin.get()
    private val preferences: Preferences = koin.get()
    private val capabilities: MessageCapabilities = koin.get()
    private val flagRepository: MessageFlagRepository = koin.get()
    private val moveRepository: MessageMoveRepository = koin.get()
    private val deleteRepository: MessageDeleteRepository = koin.get()
    private val mailSynchronizer: MailSynchronizer = koin.get()
    private val outboxSender: OutboxSender = koin.get()
    private val remoteContent: RemoteContentRepository = koin.get()
    private val newMailNotifications: NewMailNotifications = koin.get()
    private val localMessageReader: LocalMessageReader = koin.get()
    private val messageComposeOperations: MessageComposeOperations = koin.get()
    private val appCoroutineScope: CoroutineScope = koin.get(named("AppCoroutineScope"))
    private val displayFolderRepository: DisplayFolderRepository = koin.get()
    private val messageListLoader: MessageListLoader = koin.get()

    private val pump = MainLooperPump(timeout)
    private val remoteWorkQueue = RemoteWorkQueue(koin.get())

    private val folderDetailsRepository: FolderDetailsRepository = koin.get()
    private val pushController: PushController = koin.get()
    private val accountStateLoader: AccountStateLoader = koin.get()
    private val serverSettingsUpdater: AccountServerSettingsUpdater = koin.get()

    override fun addAccount(spec: AccountSpec): ClientAccount {
        val account = Account(
            uuid = UUID.randomUUID().toString(),
            emailAddress = spec.email,
            incomingServerSettings = incomingServerSettings(spec),
            outgoingServerSettings = ServerSettings(
                type = "smtp",
                host = spec.smtpHost,
                port = spec.smtpPort,
                connectionSecurity = ConnectionSecurity.NONE,
                authenticationType = spec.authType,
                username = spec.username,
                password = spec.smtpPassword,
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

    private val AccountSpec.authType: AuthType
        get() = if (oAuthSignedOut) AuthType.XOAUTH2 else AuthType.PLAIN

    private fun incomingServerSettings(spec: AccountSpec): ServerSettings = when (spec.protocol) {
        MailProtocol.IMAP -> ServerSettings(
            type = "imap",
            host = spec.incomingHost,
            port = spec.incomingPort,
            connectionSecurity = if (spec.incomingTls) ConnectionSecurity.SSL_TLS_REQUIRED else ConnectionSecurity.NONE,
            authenticationType = spec.authType,
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
        )

        MailProtocol.POP3 -> ServerSettings(
            type = "pop3",
            host = spec.incomingHost,
            port = spec.incomingPort,
            connectionSecurity = ConnectionSecurity.NONE,
            authenticationType = AuthType.PLAIN,
            username = spec.username,
            password = spec.password,
            clientCertificateAlias = null,
        )
    }

    override fun changeSettings(account: ClientAccount, settings: ClientAccountSettings) {
        // The account settings screen edits the account through its preference data store, which saves on a
        // background executor; the keys are the screen's preference keys.
        val dataStore = koin.get<AccountSettingsDataStoreFactory>().create(accountDto(account))
        with(settings) {
            deleteFromServer?.let { dataStore.putString("delete_policy", it.toDeletePolicy().name) }
            markReadOnDelete?.let { dataStore.putBoolean("mark_message_as_read_on_delete", it) }
            expunge?.let { dataStore.putString("expunge_policy", it.toExpunge().name) }
            markReadOnOpen?.let { dataStore.putBoolean("mark_message_as_read_on_view", it) }
            autoDownloadLimitBytes?.let { dataStore.putString("account_autodownload_size", it.toString()) }
            uploadSentMessages?.let { dataStore.putBoolean("upload_sent_messages", it) }
            showSyncNotifications?.let { dataStore.putBoolean("account_notify_sync", it) }
            remoteSearchResultLimit?.let { dataStore.putString("account_remote_search_num_results", it.toString()) }
            notifyNewMail?.let { dataStore.putBoolean("account_notify", it) }
        }

        val saveExecutor: ExecutorService = koin.get(named("SaveSettingsExecutorService"))
        val saved = saveExecutor.submit {}
        pump.awaitCondition("account settings to be saved") { saved.isDone }
        awaitIdle()
    }

    override fun setNotificationActions(actions: List<NotificationButton>) {
        // Same as the "Message actions" screen of the notification settings, which saves the order and how many of
        // them to show.
        val preferenceManager = koin.get<NotificationPreferenceManager>()
        val chosen = actions.map { it.token }
        val order = chosen + NotificationActionTokens.DEFAULT_ORDER.filterNot { it in chosen }
        preferenceManager.save(
            preferenceManager.getConfig().copy(messageActionsOrder = order, messageActionsCutoff = chosen.size),
        )
    }

    private val NotificationButton.token: String
        get() = when (this) {
            NotificationButton.REPLY -> NotificationActionTokens.REPLY
            NotificationButton.MARK_READ -> NotificationActionTokens.MARK_AS_READ
            NotificationButton.DELETE -> NotificationActionTokens.DELETE
            NotificationButton.STAR -> NotificationActionTokens.STAR
            NotificationButton.ARCHIVE -> NotificationActionTokens.ARCHIVE
            NotificationButton.SPAM -> NotificationActionTokens.SPAM
        }

    override fun setConfirmDeleteFromNotification(confirm: Boolean) {
        // Same as the switch in the general settings' "Confirm actions".
        val preferenceManager = koin.get<InteractionSettingsPreferenceManager>()
        preferenceManager.save(preferenceManager.getConfig().copy(isConfirmDeleteFromNotification = confirm))
    }

    override fun removeAccount(account: ClientAccount) {
        // Same as confirming "Remove account" in the account settings, which hands the work to WorkManager.
        koin.get<BackgroundAccountRemover>().removeAccountAsync(account.id)
        pump.awaitCondition("account ${account.email} to be removed") { preferences.getAccount(account.id) == null }
        awaitIdle()
    }

    override fun openFolder(account: ClientAccount, folder: FolderPath) {
        val accountDto = accountDto(account)

        // Same as MessageHomeActivity.onMessageListDisplayed() for the search of one folder of one account.
        newMailNotifications.clearForMessageList(folderSearch(accountDto, folderId(accountDto, folder)))
        awaitIdle()
    }

    override fun pullToRefresh(account: ClientAccount, folder: FolderPath) {
        startPullToRefresh(account, folder)
        awaitIdle()
    }

    override fun startPullToRefresh(account: ClientAccount, folder: FolderPath) {
        val accountDto = accountDto(account)
        val folderId = folderId(accountDto, folder)

        // Same calls as LegacyMessageListFragment.checkMail() when it shows a single folder of a single account.
        mailSynchronizer.requestFolderSync(accountDto.id, folderId, notify = false)
        outboxSender.requestSendPending(accountDto.id)
    }

    override fun syncAllAccounts() {
        // Same as the drawer's SyncAllAccounts use case.
        runSuspending("checking mail for all accounts") {
            mailSynchronizer.checkMail(
                accountId = null,
                ignoreLastCheckedTime = true,
                useManualWakeLock = true,
                notify = true,
            )
        }
        awaitIdle()
    }

    override fun enablePush(account: ClientAccount, folder: FolderPath) {
        updateFolderSettings(account, folder) { it.copy(isPushEnabled = true) }

        // Every activity does this when it's created (BaseActivity), i.e. the app is open.
        pushController.init()
        awaitIdle()
    }

    override fun setFolderVisible(account: ClientAccount, folder: FolderPath, visible: Boolean) {
        updateFolderSettings(account, folder) { it.copy(isVisible = visible) }
        awaitIdle()
    }

    override fun setFolderSyncEnabled(account: ClientAccount, folder: FolderPath, enabled: Boolean) {
        updateFolderSettings(account, folder) { it.copy(isSyncEnabled = enabled) }
        awaitIdle()
    }

    /** Same as a switch in the folder's settings (FolderSettingsDataStore). */
    private fun updateFolderSettings(
        account: ClientAccount,
        folder: FolderPath,
        change: (FolderDetails) -> FolderDetails,
    ) {
        val accountDto = accountDto(account)
        val folderId = folderId(accountDto, folder)
        pump.runInBackground("changing the settings of $folder") {
            runBlocking {
                val details = folderDetailsRepository.findById(accountDto.id, folderId).fold(
                    onSuccess = { it ?: error("Folder $folder not found") },
                    onFailure = { error("Couldn't read the settings of $folder: $it") },
                )
                folderDetailsRepository.update(accountDto.id, change(details)).fold(
                    onSuccess = {},
                    onFailure = { error("Couldn't change the settings of $folder: $it") },
                )
            }
        }
    }

    override fun clearLocalMessages(account: ClientAccount, folder: FolderPath) {
        val accountDto = accountDto(account)
        // Same as confirming "Clear local messages" in FolderSettingsViewModel.
        val folderId = folderId(accountDto, folder)
        runSuspending("clearing local messages") { deleteRepository.clearLocalMessages(accountDto.id, folderId) }
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
        userChange { flagRepository.update(item.account.id, listOf(item.databaseId), flag, newState) }
        awaitIdle()
    }

    override fun delete(account: ClientAccount, folder: FolderPath, subject: String) {
        val item = messageListItem(account, folder, subject)

        // Same as LegacyMessageListFragment.onDeleteConfirmed() in the unthreaded list; the swipe action and the menu
        // both end up there, and confirming deletes is off by default.
        userChange { deleteRepository.delete(listOf(item.messageReference)) }
        awaitIdle()
    }

    override fun archive(account: ClientAccount, folder: FolderPath, subject: String) {
        val item = messageListItem(account, folder, subject)
        checkHasArchiveFolder(account, item)
        checkCopyOrMovePossible(item, FolderOperation.MOVE)

        // Same as LegacyMessageListFragment.onArchive() in the unthreaded list.
        userChange { moveRepository.archive(listOf(item.messageReference)) }
        awaitIdle()
    }

    override fun move(account: ClientAccount, folder: FolderPath, subject: String, to: FolderPath) {
        copyOrMove(account, folder, subject, to, FolderOperation.MOVE, threaded = false)
    }

    override fun copy(account: ClientAccount, folder: FolderPath, subject: String, to: FolderPath) {
        copyOrMove(account, folder, subject, to, FolderOperation.COPY, threaded = false)
    }

    /**
     * Same as LegacyMessageListFragment.copyOrMove() after the user picked [to] in the folder picker, for a single
     * message ([threaded] false) or the thread shown under [subject] in the threaded list.
     */
    private fun copyOrMove(
        account: ClientAccount,
        folder: FolderPath,
        subject: String,
        to: FolderPath,
        operation: FolderOperation,
        threaded: Boolean,
    ) {
        val item = if (threaded) threadListItem(account, folder, subject) else messageListItem(account, folder, subject)
        val destinationFolderId = folderId(accountDto(account), to)
        checkCopyOrMovePossible(item, operation)
        require(destinationFolderId != item.folderId) { "The message is already in $to" }

        val id = item.account.id
        val references = listOf(item.messageReference)
        userChange {
            when (operation) {
                FolderOperation.MOVE if threaded ->
                    moveRepository.moveThreads(id, item.folderId, references, destinationFolderId)

                FolderOperation.MOVE -> moveRepository.move(id, item.folderId, references, destinationFolderId)

                FolderOperation.COPY if threaded ->
                    moveRepository.copyThreads(id, item.folderId, references, destinationFolderId)

                FolderOperation.COPY -> moveRepository.copy(id, item.folderId, references, destinationFolderId)
            }
        }
        awaitIdle()
    }

    override fun markAsSpam(account: ClientAccount, folder: FolderPath, subject: String) {
        val item = messageListItem(account, folder, subject)
        val spamFolderId = checkNotNull(item.account.spamFolderId) { "${account.email} has no spam folder" }
        checkCopyOrMovePossible(item, FolderOperation.MOVE)

        // Same as LegacyMessageListFragment.onSpamConfirmed(): a move to the account's spam folder.
        userChange {
            moveRepository.move(item.account.id, item.folderId, listOf(item.messageReference), spamFolderId)
        }
        awaitIdle()
    }

    override fun deleteFromOutbox(account: ClientAccount, subject: String) {
        val accountDto = accountDto(account)
        val outboxFolderId = koin.get<OutboxFolderManager>().getOutboxFolderIdSync(accountDto.uuid)
        val item =
            singleItem(messageListInfo(folderSearch(accountDto, outboxFolderId), threaded = false), subject, "Outbox")

        // Same as LegacyMessageListFragment.onDeleteConfirmed() in the outbox's unthreaded list.
        userChange { deleteRepository.delete(listOf(item.messageReference)) }
        awaitIdle()
    }

    override fun moveToDrafts(account: ClientAccount, folder: FolderPath, subject: String) {
        val item = messageListItem(account, folder, subject)

        // Same as LegacyMessageListFragment.onMoveToDraftsFolder().
        userChange { moveRepository.moveToDrafts(item.account.id, item.folderId, listOf(item.messageReference)) }
        awaitIdle()
    }

    override fun actOnSelection(messages: List<MessageSelector>, action: SelectionAction) {
        require(messages.isNotEmpty()) { "Nothing selected" }
        val items = messages.map { messageListItem(it.account, it.folder, it.subject) }
        val references = items.map(MessageListItem::messageReference)

        // Same as the action mode of LegacyMessageListFragment in the unthreaded list.
        when (action) {
            SelectionAction.MARK_READ -> setFlagForSelected(items, Flag.SEEN, true)

            SelectionAction.MARK_UNREAD -> setFlagForSelected(items, Flag.SEEN, false)

            SelectionAction.STAR -> setFlagForSelected(items, Flag.FLAGGED, true)

            SelectionAction.UNSTAR -> setFlagForSelected(items, Flag.FLAGGED, false)

            SelectionAction.DELETE -> userChange { deleteRepository.delete(references) }

            SelectionAction.ARCHIVE -> {
                items.forEach { checkCopyOrMovePossible(it, FolderOperation.MOVE) }
                userChange { moveRepository.archive(references) }
            }
        }
        awaitIdle()
    }

    /** Same as LegacyMessageListFragment.setFlagForSelected() in the unthreaded list: one call per account. */
    private fun setFlagForSelected(items: List<MessageListItem>, flag: Flag, newState: Boolean) {
        for ((_, itemsInAccount) in items.groupBy { it.account.uuid }) {
            val accountId = itemsInAccount.first().account.id
            val messageIds = itemsInAccount.map { it.databaseId }
            userChange { flagRepository.update(accountId, messageIds, flag, newState) }
        }
    }

    override fun markAllRead(account: ClientAccount, folder: FolderPath) {
        val accountDto = accountDto(account)
        val displayFolder = displayFolder(accountDto, folder)
        check(displayFolder.folder.type != FolderType.OUTBOX) { "The outbox has no \"Mark all as read\"" }
        val folderId = displayFolder.folder.id

        // Same as LegacyMessageListFragment.markAllAsRead() when showing one folder of one account.
        userChange { flagRepository.markAllAsRead(accountDto.id, folderId) }
        awaitIdle()
    }

    override fun emptyTrash(account: ClientAccount) {
        val accountDto = accountDto(account)
        checkNotNull(accountDto.trashFolderId) { "${account.email} has no trash folder, so there's no \"Empty trash\"" }

        // Same as confirming the "Empty trash" dialog in LegacyMessageListFragment.
        userChange { deleteRepository.emptyTrash(accountDto.id) }
        awaitIdle()
    }

    override fun emptySpam(account: ClientAccount) {
        val accountDto = accountDto(account)
        checkNotNull(accountDto.spamFolderId) { "${account.email} has no spam folder, so there's no \"Empty spam\"" }

        // Same as confirming the "Empty spam" dialog in LegacyMessageListFragment.
        userChange { deleteRepository.emptySpam(accountDto.id) }
        awaitIdle()
    }

    override fun expunge(account: ClientAccount, folder: FolderPath) {
        val accountDto = accountDto(account)
        check(capabilities.supportsExpunge(accountDto.id)) {
            "${account.email} can't expunge, so the message list has no \"Expunge\""
        }

        // Same as LegacyMessageListFragment.onExpunge().
        val folderId = folderId(accountDto, folder)
        userChange { deleteRepository.expunge(accountDto.id, folderId) }
        awaitIdle()
    }

    override fun loadMore(account: ClientAccount, folder: FolderPath) {
        val accountDto = accountDto(account)
        val folderId = folderId(accountDto, folder)
        check(messageListInfo(folderSearch(accountDto, folderId), threaded = false).hasMoreMessages) {
            "The message list of $folder doesn't offer to load more messages"
        }

        // Same as LegacyMessageListFragment.onFooterClicked() for a folder with more messages on the server.
        mailSynchronizer.requestMoreMessages(accountDto.id, folderId)
        awaitIdle()
    }

    override fun threadList(account: ClientAccount, folder: FolderPath): List<ClientThread> {
        return threadListItems(account, folder).map { item ->
            ClientThread(
                subject = item.subject,
                messageCount = item.threadCount.coerceAtLeast(1),
                isRead = item.isRead,
                isStarred = item.isStarred,
            )
        }
    }

    override fun deleteThread(account: ClientAccount, folder: FolderPath, subject: String) {
        val item = threadListItem(account, folder, subject)

        // Same as LegacyMessageListFragment.onDeleteConfirmed() in the threaded list.
        userChange { deleteRepository.deleteThreads(listOf(item.messageReference)) }
        awaitIdle()
    }

    override fun archiveThread(account: ClientAccount, folder: FolderPath, subject: String) {
        val item = threadListItem(account, folder, subject)
        checkHasArchiveFolder(account, item)
        checkCopyOrMovePossible(item, FolderOperation.MOVE)

        // Same as LegacyMessageListFragment.onArchive() in the threaded list.
        userChange { moveRepository.archiveThreads(listOf(item.messageReference)) }
        awaitIdle()
    }

    override fun moveThread(account: ClientAccount, folder: FolderPath, subject: String, to: FolderPath) {
        copyOrMove(account, folder, subject, to, FolderOperation.MOVE, threaded = true)
    }

    override fun copyThread(account: ClientAccount, folder: FolderPath, subject: String, to: FolderPath) {
        copyOrMove(account, folder, subject, to, FolderOperation.COPY, threaded = true)
    }

    override fun setThreadRead(account: ClientAccount, folder: FolderPath, subject: String, read: Boolean) {
        setThreadFlag(account, folder, subject, Flag.SEEN, read)
    }

    override fun setThreadStarred(account: ClientAccount, folder: FolderPath, subject: String, starred: Boolean) {
        setThreadFlag(account, folder, subject, Flag.FLAGGED, starred)
    }

    /** Same as LegacyMessageListFragment.setFlag() in the threaded list. */
    private fun setThreadFlag(account: ClientAccount, folder: FolderPath, subject: String, flag: Flag, state: Boolean) {
        val item = threadListItem(account, folder, subject)
        if (item.threadCount > 1) {
            userChange { flagRepository.updateThreads(item.account.id, listOf(item.threadRoot), flag, state) }
        } else {
            userChange { flagRepository.update(item.account.id, listOf(item.databaseId), flag, state) }
        }
        awaitIdle()
    }

    override fun open(account: ClientAccount, folder: FolderPath, subject: String): ClientMessageContent {
        val item = messageListItem(account, folder, subject)
        val accountDto = accountDto(account)

        // MessageLoaderHelper loads the message from the database and downloads it if nothing of it is there yet.
        var message = loadLocalMessage(accountDto, item)
        if (!message.isSet(Flag.X_DOWNLOADED_FULL) && !message.isSet(Flag.X_DOWNLOADED_PARTIAL)) {
            downloadMessage(item, complete = false)
            message = loadLocalMessage(accountDto, item)
        }

        // MessageViewFragment marks the message as opened once it's shown (onResume).
        userChange { flagRepository.markAsOpened(item.messageReference) }
        awaitIdle()

        return messageContent(extractForView(message))
    }

    override fun downloadCompleteMessage(
        account: ClientAccount,
        folder: FolderPath,
        subject: String,
    ): ClientMessageContent {
        val content = open(account, folder, subject)
        check(!content.isComplete) { "The message view only offers \"Download complete message\" for partial messages" }

        val accountDto = accountDto(account)
        val item = messageListItem(account, folder, subject)
        downloadMessage(item, complete = true)
        return messageContent(extractForView(loadLocalMessage(accountDto, item)))
    }

    /** Same as MessageLoaderHelper.startDownloadingMessageBody(); waits for its callback. */
    private fun downloadMessage(item: MessageListItem, complete: Boolean) {
        val done = CountDownLatch(1)
        val callback = object : MessageBodyDownloader.Callback {
            override fun onDownloadFinished(message: MessageReference) = done.countDown()
            override fun onMessageNotFound() = done.countDown()
            override fun onDownloadFailed() = done.countDown()
        }
        koin.get<MessageBodyDownloader>().download(item.messageReference, complete, callback)
        pump.awaitCondition("downloading '${item.subject}'") { done.count == 0L }
        awaitIdle()
    }

    override fun downloadAttachment(
        account: ClientAccount,
        folder: FolderPath,
        subject: String,
        fileName: String,
    ): ByteArray? {
        open(account, folder, subject)
        val accountDto = accountDto(account)
        val item = messageListItem(account, folder, subject)
        val message = loadLocalMessage(accountDto, item)
        val attachment = extractForView(message).attachments.singleOrNull { it.displayName == fileName }
            ?: error("The message '$subject' has no attachment named '$fileName'")

        if (!attachment.isContentAvailable) {
            // Same as AttachmentController.downloadAttachment().
            val part = checkNotNull(attachment.part)
            val succeeded = runSuspending("downloading attachment '$fileName'") {
                koin.get<AttachmentLoadingController>().loadAttachment(part)
            }
            awaitIdle()
            if (!succeeded) return null
        }

        val reloaded = extractForView(loadLocalMessage(accountDto, item)).attachments.single {
            it.displayName == fileName
        }
        val body = checkNotNull(reloaded.part?.body) { "Attachment '$fileName' has no content after downloading" }
        return MimeUtility.decodeBody(body).use { it.readBytes() }
    }

    /** Same as LocalMessageLoader, which loads the whole message. */
    private fun loadLocalMessage(accountDto: LegacyAccountDto, item: MessageListItem): LocalMessage {
        return pump.runInBackground("loading '${item.subject}' from the database") {
            localMessageReader.loadMessage(accountDto, item.folderId, item.messageUid)
        }
    }

    /** Same as the message view's MessageViewInfoExtractor (no OpenPGP). */
    private fun extractForView(message: LocalMessage): MessageViewInfo {
        val htmlSettings = koin.get<MessageReaderHtmlSettingsProvider>().create()
        val extractor = koin.get<MessageViewInfoExtractorFactory>().create(htmlSettings)
        return pump.runInBackground("decoding '${message.subject}'") {
            extractor.extractMessageForView(message, null, false)
        }
    }

    private fun messageContent(viewInfo: MessageViewInfo): ClientMessageContent = ClientMessageContent(
        subject = viewInfo.subject,
        text = viewInfo.text?.let { html ->
            // The message view's HTML starts with its own style sheet, which isn't part of what the user reads.
            val content = html.replace(HEAD_AND_STYLE, "")
            Html.fromHtml(content, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        },
        isComplete = !viewInfo.isMessageIncomplete,
        attachments = viewInfo.attachments.map { attachment ->
            ClientAttachment(
                fileName = attachment.displayName.orEmpty(),
                size = attachment.size,
                isDownloaded = attachment.isContentAvailable,
            )
        },
    )

    override fun searchOnServer(account: ClientAccount, folder: FolderPath, query: String): ClientRemoteSearch {
        val accountDto = accountDto(account)
        val folderId = folderId(accountDto, folder)
        val search = textSearch(accountDto, folderId, query)

        // Same as LegacyMessageListFragment.onRemoteSearchRequested(); the list shows the results once it's finished.
        var failed = false
        val finished = runSuspending("the server search for '$query'") {
            remoteContent.searchOnServer(accountDto.id, folderId, search.remoteSearchArguments, null, null)
                .onEach { event -> if (event is RemoteSearchEvent.Failed) failed = true }
                .filterIsInstance<RemoteSearchEvent.Finished>()
                .first()
        }
        awaitIdle()

        return remoteSearch(account, folder, query, search, extraResults = finished.moreResults, failed)
    }

    override fun loadMoreSearchResults(search: ClientRemoteSearch): ClientRemoteSearch {
        val extraResults = search.extraResults
        check(extraResults.isNotEmpty()) { "The search result list doesn't offer to load more results" }
        val accountDto = accountDto(search.account)
        val folderId = folderId(accountDto, search.folder)

        // Same as LegacyMessageListFragment.onFooterClicked() for a remote search with more results.
        val limit = accountDto.remoteSearchNumResults
        val (toLoad, remaining) = if (limit in 1 until extraResults.size) {
            extraResults.subList(0, limit) to extraResults.subList(limit, extraResults.size)
        } else {
            extraResults to emptyList()
        }
        runSuspending("more server search results") {
            remoteContent.loadSearchResults(accountDto.id, folderId, toLoad)
        }
        awaitIdle()

        val textSearch = textSearch(accountDto, folderId, search.query)
        return remoteSearch(search.account, search.folder, search.query, textSearch, remaining, failed = false)
    }

    private fun remoteSearch(
        account: ClientAccount,
        folder: FolderPath,
        query: String,
        search: LocalMessageSearch,
        extraResults: List<String>,
        failed: Boolean,
    ) = ClientRemoteSearch(
        account = account,
        folder = folder,
        query = query,
        results = messageListInfo(search, threaded = false).messageListItems.map { it.toClientMessage() },
        hasMoreResults = extraResults.isNotEmpty(),
        failed = failed,
        state = extraResults,
    )

    @Suppress("UNCHECKED_CAST")
    private val ClientRemoteSearch.extraResults: List<String>
        get() = state as List<String>

    /** The search MessageHomeActivity builds for a search typed in one folder of one account. */
    private fun textSearch(accountDto: LegacyAccountDto, folderId: Long, query: String) = LocalMessageSearch().apply {
        isManualSearch = true
        listOf(
            MessageSearchField.SENDER,
            MessageSearchField.TO,
            MessageSearchField.CC,
            MessageSearchField.BCC,
            MessageSearchField.SUBJECT,
            MessageSearchField.MESSAGE_CONTENTS,
        ).forEach { field -> or(SearchCondition(field, SearchAttribute.CONTAINS, query)) }
        addAccountUuid(accountDto.uuid)
        addAllowedFolder(folderId)
    }

    override fun send(account: ClientAccount, composition: Composition) {
        sendMessage(accountDto(account), composition, draftId = null)
    }

    /** Same as MessageCompose's SendMessageTask. */
    private fun sendMessage(accountDto: LegacyAccountDto, composition: Composition, draftId: Long?) {
        val message = buildMessage(accountDto, composition, isDraft = false)
        pump.runInBackground("sending '${composition.subject}'") {
            messageComposeOperations.send(accountDto, message, plaintextSubject = null, draftId = draftId)
        }
        awaitIdle()
    }

    override fun saveDraft(account: ClientAccount, composition: Composition) {
        saveDraft(accountDto(account), composition, existingDraftId = null)
    }

    override fun editDraft(account: ClientAccount, subject: String, composition: Composition) {
        val accountDto = accountDto(account)
        saveDraft(accountDto, composition, existingDraftId = draftItem(account, subject).databaseId)
    }

    /** Same as MessageCompose's SaveMessageTask; checkToSaveDraftAndSave() requires a drafts folder. */
    private fun saveDraft(accountDto: LegacyAccountDto, composition: Composition, existingDraftId: Long?) {
        check(accountDto.hasDraftsFolder()) { "${accountDto.email} has no drafts folder, so drafts can't be saved" }
        val message = buildMessage(accountDto, composition, isDraft = true)
        pump.runInBackground("saving draft '${composition.subject}'") {
            messageComposeOperations.saveDraft(accountDto, message, existingDraftId, plaintextSubject = null)
        }
        awaitIdle()
    }

    override fun sendDraft(account: ClientAccount, subject: String) {
        val accountDto = accountDto(account)
        val item = draftItem(account, subject)

        // MessageCompose loads the draft into its fields; sending builds a new message from them.
        val draft = loadLocalMessage(accountDto, item)
        val composition = Composition(
            to = draft.getRecipients(RecipientType.TO).map { it.address },
            subject = draft.subject.orEmpty(),
            text = checkNotNull(extractForView(draft).textPlainFormatted ?: extractForView(draft).text) {
                "The draft '$subject' has no text"
            },
        )
        sendMessage(accountDto, composition, draftId = item.databaseId)
    }

    override fun discardDraft(account: ClientAccount, subject: String) {
        val accountDto = accountDto(account)
        val item = draftItem(account, subject)

        // Same as MessageCompose.onDiscard() for a draft that was saved before.
        messageComposeOperations.deleteDraft(accountDto, item.databaseId)
        awaitIdle()
    }

    private fun draftItem(account: ClientAccount, subject: String): MessageListItem {
        val accountDto = accountDto(account)
        val draftsFolderId = checkNotNull(accountDto.draftsFolderId) { "${account.email} has no drafts folder" }
        return singleItem(
            messageListInfo(folderSearch(accountDto, draftsFolderId), threaded = false),
            subject,
            "Drafts",
        )
    }

    /**
     * Builds the message like MessageCompose.createMessageBuilder() for plain text without OpenPGP, quoting or
     * attachments.
     */
    private fun buildMessage(accountDto: LegacyAccountDto, composition: Composition, isDraft: Boolean): MimeMessage {
        val builder = SimpleMessageBuilder.newInstance()
        builder.setSubject(composition.subject)
            .setSentDate(Date())
            .setHideTimeZone(false)
            .setTo(composition.to.map { Address(it) })
            .setCc(emptyList())
            .setBcc(emptyList())
            .setInReplyTo(null)
            .setReferences(null)
            .setRequestReadReceipt(false)
            .setIdentity(accountDto.identities.first())
            .setReplyTo(emptyArray())
            .setMessageFormat(SimpleMessageFormat.TEXT)
            .setText(composition.text)
            .setAttachments(emptyList())
            .setInlineAttachments(emptyMap())
            .setSignature("")
            .setSignatureBeforeQuotedText(false)
            .setIdentityChanged(false)
            .setSignatureChanged(false)
            .setCursorPosition(0)
            .setMessageReference(null)
            .setDraft(isDraft)
            .setIsPgpInlineEnabled(false)
            .setQuoteStyle(QuoteStyle.PREFIX)
            .setQuotedTextMode(QuotedTextMode.NONE)
            .setQuotedText("")
            .setQuotedHtmlContent(null)
            .setReplyAfterQuote(false)

        val result = AtomicReference<Result<MimeMessage>?>(null)
        builder.buildAsync(
            object : MessageBuilder.Callback {
                override fun onMessageBuildSuccess(message: MimeMessage, isDraft: Boolean) {
                    result.set(Result.success(message))
                }

                override fun onMessageBuildCancel() {
                    result.set(Result.failure(IllegalStateException("Building the message was cancelled")))
                }

                override fun onMessageBuildException(exception: MessagingException) {
                    result.set(Result.failure(exception))
                }

                override fun onMessageBuildReturnPendingIntent(
                    pendingIntent: android.app.PendingIntent,
                    requestCode: Int,
                ) {
                    result.set(Result.failure(IllegalStateException("Building the message needs user interaction")))
                }
            },
        )
        pump.awaitCondition("building '${composition.subject}'") { result.get() != null }
        return checkNotNull(result.get()).getOrThrow()
    }

    override fun refreshFolders(account: ClientAccount) {
        // Same as the refresh action of ManageFoldersFragment.
        mailSynchronizer.requestFolderListRefresh(accountDto(account).id)
        awaitIdle()
    }

    override fun updatePassword(account: ClientAccount, password: String) {
        updateServerPassword(account, password, isIncoming = true)
    }

    override fun updateOutgoingPassword(account: ClientAccount, password: String) {
        updateServerPassword(account, password, isIncoming = false)
    }

    private fun updateServerPassword(account: ClientAccount, password: String, isIncoming: Boolean) {
        // Same as the server settings screens: they load the account's settings (LoadAccountState), the user edits
        // the password, and the save step (SaveServerSettings) hands the settings to the updater. The connection check
        // the screens make before saving isn't repeated here.
        val server = if (isIncoming) "incoming" else "outgoing"
        pump.runInBackground("saving the $server server password") {
            runBlocking {
                val state = accountStateLoader.loadAccountState(account.id)
                    ?: error("Account ${account.email} not found in the app")
                val settings = if (isIncoming) state.incomingServerSettings else state.outgoingServerSettings
                val current = checkNotNull(settings) { "Account without $server settings" }
                val result = serverSettingsUpdater.updateServerSettings(
                    accountUuid = account.id,
                    isIncoming = isIncoming,
                    serverSettings = current.copy(password = password),
                    authorizationState = state.authorizationState,
                )
                check(result is AccountUpdaterResult.Success) { "Saving the server settings failed: $result" }
            }
        }
        awaitIdle()
    }

    override fun folderList(account: ClientAccount): List<ClientFolder> {
        return displayFolders(accountDto(account), includeHidden = false).map { displayFolder ->
            ClientFolder(
                path = displayFolder.toFolderPath(),
                unreadCount = displayFolder.unreadMessageCount,
                isLocalOnly = displayFolder.folder.isLocalOnly,
            )
        }
    }

    override fun messageList(account: ClientAccount, folder: FolderPath): List<ClientMessage> {
        val items = messageListItems(account, folder)

        // Same as LegacyMessageListFragment when it shows messages: it checks their accounts for auth problems.
        items.map { it.account.id }.toSet().forEach(mailSynchronizer::checkAuthenticationProblem)
        awaitIdle()

        return items.map { it.toClientMessage() }
    }

    override fun outbox(account: ClientAccount): List<ClientMessage> {
        val accountDto = accountDto(account)
        val outboxFolderId = koin.get<OutboxFolderManager>().getOutboxFolderIdSync(accountDto.uuid)
        return messageListInfo(folderSearch(accountDto, outboxFolderId), threaded = false)
            .messageListItems.map { it.toClientMessage() }
    }

    override fun unifiedInbox(): List<ClientAccountMessage> {
        // Same search as MessageHomeActivity.createUnifiedInboxIntent(), unthreaded.
        val search = SearchAccount.createUnifiedFoldersSearch(title = "Unified Inbox", detail = "").relatedSearch
        return messageListInfo(search, threaded = false).messageListItems.map { item ->
            ClientAccountMessage(accountEmail = item.account.email, message = item.toClientMessage())
        }
    }

    override fun close() {
        try {
            remoteWorkQueue.stop()
        } finally {
            pump.close()
        }
    }

    private fun MessageListItem.toClientMessage() = ClientMessage(
        subject = subject,
        senderAddress = displayAddress?.address,
        senderName = displayName.toString(),
        isRead = isRead,
        isStarred = isStarred,
    )

    private fun checkHasArchiveFolder(account: ClientAccount, item: MessageListItem) {
        checkNotNull(item.account.archiveFolderId) {
            "${account.email} has no archive folder; the app would offer to set one up instead of archiving"
        }
    }

    /** The checks of LegacyMessageListFragment.checkCopyOrMovePossible(), which shows a toast instead of acting. */
    private fun checkCopyOrMovePossible(item: MessageListItem, operation: FolderOperation) {
        when (operation) {
            FolderOperation.MOVE -> {
                check(capabilities.isMoveCapable(item.account.id)) { "The account can't move messages" }
                check(capabilities.isMoveCapable(item.messageReference)) {
                    "The message '${item.subject}' can't be moved yet (not synced)"
                }
            }

            FolderOperation.COPY -> {
                check(capabilities.isCopyCapable(item.account.id)) { "The account can't copy messages" }
                check(capabilities.isCopyCapable(item.messageReference)) {
                    "The message '${item.subject}' can't be copied yet (not synced)"
                }
            }
        }
    }

    private fun messageListItem(account: ClientAccount, folder: FolderPath, subject: String): MessageListItem {
        return singleItem(messageListInfo(account, folder, threaded = false), subject, folder.toString())
    }

    private fun threadListItem(account: ClientAccount, folder: FolderPath, subject: String): MessageListItem {
        return singleItem(messageListInfo(account, folder, threaded = true), subject, "threaded $folder")
    }

    private fun singleItem(info: MessageListInfo, subject: String, where: String): MessageListItem {
        val matches = info.messageListItems.filter { it.subject == subject }
        return matches.singleOrNull()
            ?: error("Expected one message with subject '$subject' in $where, found ${matches.size}")
    }

    private fun messageListItems(account: ClientAccount, folder: FolderPath): List<MessageListItem> =
        messageListInfo(account, folder, threaded = false).messageListItems

    private fun threadListItems(account: ClientAccount, folder: FolderPath): List<MessageListItem> =
        messageListInfo(account, folder, threaded = true).messageListItems

    private fun messageListInfo(account: ClientAccount, folder: FolderPath, threaded: Boolean): MessageListInfo {
        val accountDto = accountDto(account)
        return messageListInfo(folderSearch(accountDto, folderId(accountDto, folder)), threaded)
    }

    private fun folderSearch(accountDto: LegacyAccountDto, folderId: Long) = LocalMessageSearch().apply {
        addAccountUuid(accountDto.uuid)
        addAllowedFolder(folderId)
    }

    private fun messageListInfo(search: LocalMessageSearch, threaded: Boolean): MessageListInfo {
        val config = MessageListConfig(
            search = search,
            showingThreadedList = threaded,
            sortType = SortType.SORT_DATE,
            sortAscending = false,
            sortDateAscending = false,
            activeMessage = null,
            sortOverrides = emptyMap(),
        )

        // Note: the loader logs and swallows errors, returning an empty list, just like the UI shows one.
        return pump.runInBackground("message list") {
            messageListLoader.getMessageList(config)
        }
    }

    /** The folder list the drawer shows; with [includeHidden], also the folders the user has hidden. */
    private fun displayFolders(accountDto: LegacyAccountDto, includeHidden: Boolean): List<DisplayFolder> {
        return pump.runInBackground("folder list") {
            runBlocking {
                displayFolderRepository.getDisplayFoldersFlow(accountDto, includeHiddenFolders = includeHidden).first()
            }
        }
    }

    private fun folderId(accountDto: LegacyAccountDto, path: FolderPath): Long = displayFolder(
        accountDto,
        path,
    ).folder.id

    /** Finds a folder the way the user would, e.g. in "Manage folders", where hidden folders are listed too. */
    private fun displayFolder(accountDto: LegacyAccountDto, path: FolderPath): DisplayFolder {
        val folders = displayFolders(accountDto, includeHidden = true)
        // The server may have a folder with the same name as a local-only one (James has an "Outbox"); the local one
        // is reached through outbox().
        val matches = folders.filter { it.toFolderPath() == path }
        return matches.singleOrNull { !it.folder.isLocalOnly } ?: matches.firstOrNull()
            ?: error("Folder $path not in the app's folder list: ${folders.map { it.toFolderPath() }}")
    }

    private fun accountDto(account: ClientAccount): LegacyAccountDto {
        return preferences.getAccount(account.id) ?: error("Account ${account.email} not found in the app")
    }

    override fun awaitIdle() {
        remoteWorkQueue.awaitIdle(pump)
    }

    /** Starts [change] like the UI does, see `launchUserChange`, and waits until the call returns. */
    private fun userChange(change: suspend () -> Unit) {
        runSuspending("a change to messages") { change() }
    }

    /** Runs [block] on the main thread like UI code does, and waits for its result. */
    private fun <T> runSuspending(description: String, block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        appCoroutineScope.launchUserChange {
            result.set(runCatching { block() })
        }
        pump.awaitCondition(description) { result.get() != null }
        return checkNotNull(result.get()).getOrThrow()
    }

    /** Maps the name shown in the app back to a logical path, using the server's hierarchy delimiter. */
    private fun DisplayFolder.toFolderPath(): FolderPath {
        if (folder.type == FolderType.INBOX) return FolderPath.INBOX

        val segments = if (pathDelimiter.isEmpty()) listOf(folder.name) else folder.name.split(pathDelimiter)
        return FolderPath(segments)
    }

    private enum class FolderOperation { MOVE, COPY }

    private fun DeleteFromServer.toDeletePolicy() = when (this) {
        DeleteFromServer.NEVER -> DeletePolicy.NEVER
        DeleteFromServer.ON_DELETE -> DeletePolicy.ON_DELETE
        DeleteFromServer.MARK_AS_READ -> DeletePolicy.MARK_AS_READ
    }

    private fun ExpungeMode.toExpunge() = when (this) {
        ExpungeMode.IMMEDIATELY -> Expunge.EXPUNGE_IMMEDIATELY
        ExpungeMode.ON_POLL -> Expunge.EXPUNGE_ON_POLL
        ExpungeMode.MANUALLY -> Expunge.EXPUNGE_MANUALLY
    }

    private companion object {
        val DEFAULT_TIMEOUT = 2.minutes
        val HEAD_AND_STYLE =
            Regex("<head.*?</head>|<style.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        const val MESSAGE_DISPLAY_COUNT = 25
    }
}
