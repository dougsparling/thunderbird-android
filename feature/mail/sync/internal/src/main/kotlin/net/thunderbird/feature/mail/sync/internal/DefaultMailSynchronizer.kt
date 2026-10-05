package net.thunderbird.feature.mail.sync.internal

import app.k9mail.legacy.mailstore.FolderDetailsAccessor
import com.fsck.k9.K9
import com.fsck.k9.backend.api.Backend
import com.fsck.k9.mail.power.PowerManager
import com.fsck.k9.mailstore.LocalFolder
import com.fsck.k9.notification.NotificationController
import com.fsck.k9.notification.NotificationStrategy
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.exception.rootCauseMessage
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.MailSynchronizer
import net.thunderbird.feature.mail.sync.api.SyncEvent
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import net.thunderbird.feature.mail.sync.internal.engine.WorkPriority

private const val TAG = "MailSynchronizer"

private const val FOLDER_LIST_STALENESS_THRESHOLD = 30 * 60 * 1000L
private const val MILLIS_PER_MINUTE = 60 * 1000L

@OptIn(ExperimentalTime::class)
@Suppress("LongParameterList", "TooManyFunctions")
internal class DefaultMailSynchronizer(
    private val accounts: AccountStores,
    private val serializer: RemoteWorkSerializer,
    private val pendingCommands: PendingCommandQueue,
    private val events: SyncEventBus,
    private val serverErrorNotifier: ServerErrorNotifier,
    private val outboxSender: DefaultOutboxSender,
    private val localMessages: LocalMessages,
    private val notificationController: NotificationController,
    private val notificationStrategy: NotificationStrategy,
    private val powerManager: PowerManager,
    private val clock: Clock,
    private val logger: Logger,
    private val syncDebugLogger: Logger,
) : MailSynchronizer {
    override fun requestFolderSync(accountId: AccountId, folderId: Long, notify: Boolean) {
        val account = accounts.get(accountId)
        serializer.enqueue("synchronizeMailbox", WorkPriority.BACKGROUND) {
            synchronizeMailboxBlocking(account, folderId, notify, failures = null, NotificationState())
        }
    }

    override fun requestMoreMessages(accountId: AccountId, folderId: Long) {
        val account = accounts.get(accountId)
        serializer.enqueue("loadMoreMessages", WorkPriority.BACKGROUND) {
            loadMoreMessagesBlocking(account, folderId)
        }
    }

    override fun requestFolderListRefresh(accountId: AccountId) {
        val account = accounts.get(accountId)
        serializer.enqueue("refreshFolderList", WorkPriority.FOREGROUND) {
            refreshFolderListBlocking(account)
        }
    }

    override suspend fun refreshFolderList(accountId: AccountId) {
        val account = accounts.get(accountId)
        val done = CompletableDeferred<Unit>()
        serializer.enqueue("refreshFolderListBlocking", WorkPriority.BACKGROUND) {
            try {
                refreshFolderListBlocking(account)
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }

    override fun requestCheckMail(
        accountId: AccountId?,
        ignoreLastCheckedTime: Boolean,
        useManualWakeLock: Boolean,
        notify: Boolean,
    ) {
        val account = accountId?.let(accounts::find)
        startCheckMail(account, ignoreLastCheckedTime, useManualWakeLock, notify, failures = null, onFinished = null)
    }

    override suspend fun checkMail(
        accountId: AccountId?,
        ignoreLastCheckedTime: Boolean,
        useManualWakeLock: Boolean,
        notify: Boolean,
    ) {
        val account = accountId?.let(accounts::find)
        val done = CompletableDeferred<Unit>()
        startCheckMail(account, ignoreLastCheckedTime, useManualWakeLock, notify, failures = null) {
            done.complete(Unit)
        }
        done.await()
    }

    override suspend fun syncPeriodically(accountId: AccountId): Boolean {
        val account = accounts.get(accountId)
        val failures = FolderSyncFailures()
        val result = CompletableDeferred<Boolean>()
        startCheckMail(
            account,
            ignoreLastCheckedTime = false,
            useManualWakeLock = false,
            notify = true,
            failures = failures,
        ) {
            // Recorded when the check finishes, even if the caller stopped waiting for it.
            result.complete(recordPeriodicSync(account, failures))
        }

        logger.verbose(TAG) { "syncPeriodically($account) waiting for the mail check to finish" }
        return result.await().also {
            logger.verbose(TAG) { "syncPeriodically($account) mail check finished" }
        }
    }

    private fun recordPeriodicSync(account: LegacyAccountDto, failures: FolderSyncFailures): Boolean {
        val success = !failures.anyFailed
        if (success) {
            val now = clock.now().toEpochMilliseconds()
            logger.verbose(TAG) { "Account $account successfully synced @ $now" }
            account.lastSyncTime = now
            accounts.save(account)
        }

        return success
    }

    override suspend fun syncPushedFolder(accountId: AccountId, folderServerId: String) {
        val account = accounts.get(accountId)
        val folderId = accounts.folderId(account, folderServerId)

        val done = CompletableDeferred<Unit>()
        serializer.enqueue("synchronizeMailbox", WorkPriority.BACKGROUND) {
            try {
                synchronizeMailboxBlocking(account, folderId, notify = true, failures = null, NotificationState())
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }

    override fun reportError(accountId: AccountId, exception: Exception) {
        serverErrorNotifier.handleException(accounts.get(accountId), exception)
    }

    override fun checkAuthenticationProblem(accountId: AccountId) {
        serverErrorNotifier.checkAuthenticationProblem(accounts.get(accountId))
    }

    override fun observeEvents(): Flow<SyncEvent> = events.observe()

    /**
     * Checks mail of [account] or, if it's `null`, of all accounts. [onFinished] is called when the check is done;
     * folder syncs that fail are recorded in [failures].
     */
    @Suppress("TooGenericExceptionCaught")
    private fun startCheckMail(
        account: LegacyAccountDto?,
        ignoreLastCheckedTime: Boolean,
        useManualWakeLock: Boolean,
        notify: Boolean,
        failures: FolderSyncFailures?,
        onFinished: (() -> Unit)?,
    ) {
        val wakeLock = if (useManualWakeLock) {
            powerManager.newWakeLock("K9 MessagingController.checkMail").apply {
                setReferenceCounted(false)
                acquire(K9.MANUAL_WAKE_LOCK_TIMEOUT.toLong())
            }
        } else {
            null
        }

        events.emit(SyncEvent.CheckMailStarted(account?.id))

        serializer.enqueue("checkMail", WorkPriority.BACKGROUND) {
            try {
                logger.info(TAG) { "Starting mail check" }

                val accountsToCheck = if (account != null) listOf(account) else accounts.getAll()
                for (accountToCheck in accountsToCheck) {
                    checkMailForAccount(accountToCheck, ignoreLastCheckedTime, notify, failures)
                }
            } catch (e: Exception) {
                logger.error(TAG, e) { "Unable to synchronize mail" }
            }

            serializer.enqueue("finalize sync", WorkPriority.BACKGROUND) {
                logger.info(TAG) { "Finished mail sync" }

                wakeLock?.release()

                events.emit(SyncEvent.CheckMailFinished(account?.id))
                onFinished?.invoke()
            }
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun checkMailForAccount(
        account: LegacyAccountDto,
        ignoreLastCheckedTime: Boolean,
        notify: Boolean,
        failures: FolderSyncFailures?,
    ) {
        logger.info(TAG) { "Synchronizing account $account" }

        val notificationState = NotificationState()

        outboxSender.sendPendingInBackground(account)

        refreshFolderListIfStale(account)

        try {
            val localStore = accounts.localStore(account)
            for (folder in localStore.getPersonalNamespaces(false)) {
                folder.open()

                if (!folder.isVisible) {
                    // Never sync a folder that isn't displayed
                    continue
                }

                if (!folder.isSyncEnabled) {
                    // Do not sync folders that are not enabled for sync.
                    continue
                }

                serializer.enqueue("sync" + folder.serverId, WorkPriority.BACKGROUND) {
                    synchronizeFolder(account, folder, ignoreLastCheckedTime, notify, failures, notificationState)
                }
            }
        } catch (e: MessagingException) {
            logger.error(TAG, e) { "Unable to synchronize account $account" }
        } finally {
            serializer.enqueue("clear notification flag for $account", WorkPriority.BACKGROUND) {
                logger.verbose(TAG) { "Clearing notification flag for $account" }

                notificationController.clearFetchingMailNotification(account)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private fun synchronizeFolder(
        account: LegacyAccountDto,
        folder: LocalFolder,
        ignoreLastCheckedTime: Boolean,
        notify: Boolean,
        failures: FolderSyncFailures?,
        notificationState: NotificationState,
    ) {
        logger.verbose(TAG) { "Folder ${folder.serverId} was last synced @ ${folder.lastChecked}" }

        if (!ignoreLastCheckedTime) {
            val lastCheckedTime = folder.lastChecked
            val now = clock.now().toEpochMilliseconds()

            // If the time this folder was last checked lies in the future, we better ignore this and sync now.
            if (lastCheckedTime <= now) {
                val syncInterval = account.automaticCheckIntervalMinutes * MILLIS_PER_MINUTE
                val nextSyncTime = lastCheckedTime + syncInterval
                if (nextSyncTime > now) {
                    logger.verbose(TAG) {
                        "Not syncing folder ${folder.serverId}, previously synced @ $lastCheckedTime which would " +
                            "be too recent for the account sync interval"
                    }
                    return
                }
            }
        }

        try {
            showFetchingMailNotificationIfNecessary(account, folder)
            try {
                synchronizeMailboxBlocking(account, folder.databaseId, notify, failures, notificationState)
            } finally {
                showEmptyFetchingMailNotificationIfNecessary(account)
            }
        } catch (e: Exception) {
            logger.error(TAG, e) { "Exception while processing folder $account:${folder.serverId}" }
        }
    }

    private fun showFetchingMailNotificationIfNecessary(account: LegacyAccountDto, folder: LocalFolder) {
        if (account.isNotifySync) {
            notificationController.showFetchingMailNotification(account, folder)
        }
    }

    private fun showEmptyFetchingMailNotificationIfNecessary(account: LegacyAccountDto) {
        if (account.isNotifySync) {
            notificationController.showEmptyFetchingMailNotification(account)
        }
    }

    private fun loadMoreMessagesBlocking(account: LegacyAccountDto, folderId: Long) {
        val messageStore = accounts.messageStore(account)
        val visibleLimit = messageStore.getFolder(folderId, FolderDetailsAccessor::visibleLimit)
        if (visibleLimit == null) {
            logger.verbose(TAG) { "loadMoreMessages($account, $folderId): Folder not found" }
            return
        }

        if (visibleLimit > 0) {
            val newVisibleLimit = visibleLimit + account.displayCount
            messageStore.setVisibleLimit(folderId, newVisibleLimit)
        }

        synchronizeMailboxBlocking(account, folderId, notify = false, failures = null, NotificationState())
    }

    private fun synchronizeMailboxBlocking(
        account: LegacyAccountDto,
        folderId: Long,
        notify: Boolean,
        failures: FolderSyncFailures?,
        notificationState: NotificationState,
    ) {
        refreshFolderListIfStale(account)

        val backend = accounts.backend(account)
        syncFolder(account, folderId, notify, failures, backend, notificationState)
    }

    private fun refreshFolderListIfStale(account: LegacyAccountDto) {
        val lastFolderListRefresh = account.lastFolderListRefreshTime
        val now = clock.now().toEpochMilliseconds()

        if (lastFolderListRefresh > now || lastFolderListRefresh + FOLDER_LIST_STALENESS_THRESHOLD <= now) {
            logger.debug(TAG) { "Last folder list refresh @ $lastFolderListRefresh. Refreshing now…" }
            refreshFolderListBlocking(account)
        } else {
            logger.debug(TAG) { "Last folder list refresh @ $lastFolderListRefresh. Not refreshing now." }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun refreshFolderListBlocking(account: LegacyAccountDto) {
        try {
            if (serverErrorNotifier.isAuthenticationProblem(account, incoming = true)) {
                logger.debug(TAG) { "Authentication will fail. Skip refreshing the folder list." }
                serverErrorNotifier.handleAuthenticationFailure(account, incoming = true)
                return
            }

            val backend = accounts.backend(account)
            val folderPathDelimiter = backend.refreshFolderList()
            if (!folderPathDelimiter.isNullOrEmpty() && folderPathDelimiter != account.folderPathDelimiter) {
                account.folderPathDelimiter = folderPathDelimiter
            }

            val now = clock.now().toEpochMilliseconds()
            logger.debug(TAG) { "Folder list successfully refreshed @ $now" }

            account.lastFolderListRefreshTime = now
            accounts.save(account)
        } catch (e: Exception) {
            logger.error(TAG, e) { "Could not refresh folder list for account $account" }
            serverErrorNotifier.handleException(account, e)
        }
    }

    @Suppress("TooGenericExceptionCaught", "LongParameterList", "ReturnCount")
    private fun syncFolder(
        account: LegacyAccountDto,
        folderId: Long,
        notify: Boolean,
        failures: FolderSyncFailures?,
        backend: Backend,
        notificationState: NotificationState,
    ) {
        if (serverErrorNotifier.isAuthenticationProblem(account, incoming = true)) {
            logger.debug(TAG) { "Authentication will fail. Skip synchronizing folder $folderId." }
            serverErrorNotifier.handleAuthenticationFailure(account, incoming = true)
            return
        }

        var commandException: Exception? = null
        try {
            pendingCommands.processNow(account)
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failure processing command, but allow message sync attempt" }
            commandException = e
        }

        val localFolder = try {
            accounts.localStore(account).getFolder(folderId).apply { open() }
        } catch (e: MessagingException) {
            syncDebugLogger.error("MessagingException") { e.message.orEmpty() }
            logger.error(TAG, e) { "syncFolder: Couldn't load local folder $folderId" }
            return
        }

        // We can't sync local folders
        if (localFolder.isLocalOnly) {
            return
        }

        val suppressNotifications = if (notify) {
            val lastChecked = accounts.messageStore(account).getFolder(folderId, FolderDetailsAccessor::lastChecked)
            lastChecked == null
        } else {
            true
        }

        val folderServerId = localFolder.serverId
        val syncConfig = createSyncConfig(account)
        val syncListener = FolderSyncListener(
            account = account,
            failures = failures,
            suppressNotifications = suppressNotifications,
            notificationState = notificationState,
            accounts = accounts,
            localMessages = localMessages,
            events = events,
            serverErrorNotifier = serverErrorNotifier,
            notificationController = notificationController,
            notificationStrategy = notificationStrategy,
        )

        backend.sync(folderServerId, syncConfig, syncListener)

        if (commandException != null && !syncListener.syncFailed) {
            val rootMessage = commandException.rootCauseMessage
            syncDebugLogger.error("MessagingException") { rootMessage.orEmpty() }
            logger.error(TAG) { "Root cause failure in $account:$folderServerId was '$rootMessage'" }
            accounts.messageStore(account).setStatus(folderId, rootMessage)

            // Only the mail check this sync is part of learns about the failure.
            failures?.anyFailed = true
        }
    }
}
