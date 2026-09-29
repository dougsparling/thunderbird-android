package net.thunderbird.android.scenario.harness

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
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
import com.fsck.k9.controller.push.PushController
import com.fsck.k9.job.MailSyncWorkerManager
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
import net.thunderbird.components.core.outcome.fold
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.SortType
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.folder.api.FolderType
import net.thunderbird.feature.mail.folder.api.data.repository.FolderDetailsRepository
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.mail.testserver.fixture.FolderPath
import org.koin.core.Koin
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNetworkCapabilities

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
 * System events go through the platform: [periodicSyncDue] runs the app's scheduled WorkManager jobs through
 * WorkManager's test driver, so it keeps working when the sync core behind those jobs changes.
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

    private val context: Context = koin.get()
    private val clock: ScenarioClock = koin.get()
    private val workManager: WorkManager = installTestWorkManager(koin)
    private val workTestDriver: TestDriver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))

    init {
        // Platform state a device would provide. Robolectric's defaults differ:
        // - Push needs exact alarms to refresh its connection. On a device the user grants this permission.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // - The device is online. Robolectric's active network lacks the internet capability the app checks for.
        setOnline()
    }

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
                // instead; addAccount waits for it. With an interval, the app schedules a periodic WorkManager job
                // that only runs when the scenario calls periodicSyncDue().
                checkFrequencyInMinutes = spec.checkIntervalMinutes ?: LegacyAccountDto.INTERVAL_MINUTES_NEVER,
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

    override fun periodicSyncDue() {
        val scheduled = workManager.getWorkInfosByTag(MailSyncWorkerManager.MAIL_SYNC_TAG).get()
            .filterNot { it.state.isFinished }
        check(scheduled.isNotEmpty()) {
            "No periodic mail sync is scheduled. Add the account with client.account(user, checkIntervalMinutes = ...)."
        }

        // "Due" means the check interval has passed; the app skips folders it checked more recently than that.
        val longestInterval = preferences.getAccounts()
            .map { it.automaticCheckIntervalMinutes }
            .filter { it > 0 }
            .max()
        clock.advanceBy(longestInterval.minutes)

        // The work runs synchronously inside the test driver calls (SynchronousExecutor), so run them off the main
        // thread while the main looper keeps being serviced.
        pump.runInBackground("periodic mail sync") {
            scheduled.forEach { work ->
                // The work runs once all of these are met. Constraints come last because WorkManager's test scheduler
                // resets them after every run, so each call runs the job exactly once.
                workTestDriver.setInitialDelayMet(work.id)
                workTestDriver.setPeriodDelayMet(work.id)
                workTestDriver.setAllConstraintsMet(work.id)
            }
        }
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

    private fun setOnline() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance().also { networkCapabilities ->
            shadowOf(networkCapabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        shadowOf(connectivityManager).setNetworkCapabilities(connectivityManager.activeNetwork, capabilities)
    }

    /**
     * Replaces WorkManager with its test implementation, which runs work only when the test driver says its delay and
     * constraints are met. Must happen before the app first asks Koin for its WorkManager, which caches the instance.
     */
    private fun installTestWorkManager(koin: Koin): WorkManager {
        val configuration = Configuration.Builder()
            .setWorkerFactory(koin.get<WorkerFactory>())
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)

        val testWorkManager = WorkManager.getInstance(context)
        check(koin.get<WorkManager>() === testWorkManager) {
            "The app obtained WorkManager before the scenario driver could replace it with the test implementation"
        }
        return testWorkManager
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
