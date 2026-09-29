package net.thunderbird.android.scenario.harness

import app.k9mail.feature.account.common.domain.entity.Account
import app.k9mail.feature.account.common.domain.entity.AccountOptions
import app.k9mail.feature.account.setup.AccountSetupExternalContract.AccountCreator
import app.k9mail.feature.account.setup.AccountSetupExternalContract.AccountCreator.AccountCreatorResult
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import app.k9mail.legacy.ui.folder.DisplayFolder
import app.k9mail.legacy.ui.folder.DisplayFolderRepository
import com.fsck.k9.Preferences
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.controller.MessagingControllerWrapper
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.store.imap.ImapStoreSettings
import com.fsck.k9.ui.messagelist.MessageListConfig
import com.fsck.k9.ui.messagelist.MessageListItem
import com.fsck.k9.ui.messagelist.MessageListLoader
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.SortType
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.folder.api.FolderType
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.mail.testserver.fixture.FolderPath
import org.koin.core.Koin

/**
 * [ScenarioDriver] for the current app, built on the legacy `MessagingController` sync core.
 *
 * Actions make the same calls as the UI:
 * - account setup goes through the app's [AccountCreator], the last step of the setup wizard,
 * - pull to refresh and marking read go through [MessagingControllerWrapper] like `LegacyMessageListFragment`, the
 *   message list shown while the `enable_message_list_new_state` feature flag is off (the default),
 * - the folder list comes from [DisplayFolderRepository], like the folder drawer,
 * - the message list comes from [MessageListLoader], like the message list screen (unthreaded, by date).
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
                // No periodic sync, so WorkManager never syncs behind the scenario's back. With this setting the app
                // runs one mail check right after setup; addAccount waits for it.
                checkFrequencyInMinutes = LegacyAccountDto.INTERVAL_MINUTES_NEVER,
                messageDisplayCount = MESSAGE_DISPLAY_COUNT,
                showNotification = false,
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

    override fun markRead(account: ClientAccount, folder: FolderPath, subject: String) {
        val matches = messageListItems(account, folder).filter { it.subject == subject }
        val item = matches.singleOrNull()
            ?: error("Expected one message with subject '$subject' in $folder, found ${matches.size}")

        messagingControllerWrapper.setFlag(item.account.id, listOf(item.databaseId), Flag.SEEN, true)
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

    private fun messageListItems(account: ClientAccount, folder: FolderPath): List<MessageListItem> {
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
            messageListLoader.getMessageList(config).messageListItems
        }
    }

    private fun displayFolders(accountDto: LegacyAccountDto): List<DisplayFolder> {
        return pump.runInBackground("folder list") {
            runBlocking {
                displayFolderRepository.getDisplayFoldersFlow(accountDto, includeHiddenFolders = false).first()
            }
        }
    }

    private fun folderId(accountDto: LegacyAccountDto, path: FolderPath): Long {
        val folders = displayFolders(accountDto)
        val match = folders.firstOrNull { it.toFolderPath() == path }
            ?: error("Folder $path not in the app's folder list: ${folders.map { it.toFolderPath() }}")
        return match.folder.id
    }

    private fun accountDto(account: ClientAccount): LegacyAccountDto {
        return preferences.getAccount(account.id) ?: error("Account ${account.email} not found in the app")
    }

    private fun awaitIdle() {
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
