package net.thunderbird.feature.mail.sync.internal

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.notification.NotificationController
import com.fsck.k9.notification.NotificationStrategy
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.sync.api.SyncEvent

/** Whether a new-mail notification was shown during one mail check, so later ones are silent. */
internal class NotificationState {
    var wasNotified: Boolean = false
}

/** Records that syncing a folder failed during one mail check (for the scheduled check). */
internal class FolderSyncFailures {
    @Volatile
    var anyFailed: Boolean = false
}

/**
 * Turns a backend's progress while syncing one folder into [SyncEvent]s and new-mail notifications.
 *
 * New messages are only notified of unless [suppressNotifications].
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class FolderSyncListener(
    private val account: LegacyAccountDto,
    private val failures: FolderSyncFailures?,
    private val suppressNotifications: Boolean,
    private val notificationState: NotificationState,
    private val accounts: AccountStores,
    private val localMessages: LocalMessages,
    private val events: SyncEventBus,
    private val serverErrorNotifier: ServerErrorNotifier,
    private val notificationController: NotificationController,
    private val notificationStrategy: NotificationStrategy,
) : SyncListener {
    private val localStore = accounts.localStoreOrThrow(account)

    var syncFailed = false
        private set

    override fun syncStarted(folderServerId: String) {
        val folderId = accounts.folderId(account, folderServerId)
        events.emit(SyncEvent.FolderSyncStarted(account.id, folderId))
    }

    override fun syncAuthenticationSuccess() {
        serverErrorNotifier.clearAuthenticationErrorNotification(
            account,
            incoming = true,
            clearOnlyForOAuthAccounts = false,
        )
        notificationController.clearAuthenticationErrorNotification(account, true)
    }

    override fun syncHeadersStarted(folderServerId: String) = Unit

    override fun syncHeadersProgress(folderServerId: String, completed: Int, total: Int) {
        events.emit(SyncEvent.FolderHeadersProgress(account.id, folderServerId, completed, total))
    }

    override fun syncHeadersFinished(folderServerId: String, totalMessagesInMailbox: Int, numNewMessages: Int) {
        events.emit(SyncEvent.FolderHeadersFinished(account.id, folderServerId, totalMessagesInMailbox, numNewMessages))
    }

    override fun syncProgress(folderServerId: String, completed: Int, total: Int) {
        val folderId = accounts.folderId(account, folderServerId)
        events.emit(SyncEvent.FolderSyncProgress(account.id, folderId, completed, total))
    }

    override fun syncNewMessage(folderServerId: String, messageServerId: String, isOldMessage: Boolean) {
        // Send a notification of this message
        val message = loadMessage(folderServerId, messageServerId)
        val localFolder = message.folder
        if (!suppressNotifications &&
            notificationStrategy.shouldNotifyForMessage(account, localFolder, message, isOldMessage)
        ) {
            // Notify with the localMessage so that we don't have to recalculate the content preview.
            val silent = notificationState.wasNotified
            notificationController.addNewMailNotification(account, message, silent)
            notificationState.wasNotified = true
        }
    }

    override fun syncRemovedMessage(folderServerId: String, messageServerId: String) {
        val folderId = accounts.folderId(account, folderServerId)
        val messageReference = MessageReference(account.uuid, folderId, messageServerId)
        notificationController.removeNewMailNotification(account, messageReference)
    }

    override fun syncFlagChanged(folderServerId: String, messageServerId: String) {
        var shouldBeNotifiedOf = false
        val message = loadMessage(folderServerId, messageServerId)
        if (message.isSet(Flag.DELETED) || localMessages.isHidden(message)) {
            syncRemovedMessage(folderServerId, message.uid)
        } else {
            val localFolder = message.folder
            if (notificationStrategy.shouldNotifyForMessage(account, localFolder, message, false)) {
                shouldBeNotifiedOf = true
            }
        }

        // we're only interested in messages that need removing
        if (!shouldBeNotifiedOf) {
            notificationController.removeNewMailNotification(account, message.makeMessageReference())
        }
    }

    override fun syncFinished(folderServerId: String) {
        val folderId = accounts.folderId(account, folderServerId)
        events.emit(SyncEvent.FolderSyncFinished(account.id, folderId))
    }

    override fun syncFailed(folderServerId: String, message: String, exception: Exception?) {
        syncFailed = true

        if (exception is AuthenticationFailedException) {
            serverErrorNotifier.handleAuthenticationFailure(account, incoming = true)
        } else if (exception != null) {
            serverErrorNotifier.notifyUserIfCertificateProblem(account, exception, incoming = true)
        }

        val folderId = accounts.folderId(account, folderServerId)
        events.emit(SyncEvent.FolderSyncFailed(account.id, folderId, message))
        failures?.anyFailed = true
    }

    override fun folderStatusChanged(folderServerId: String) {
        val folderId = accounts.folderId(account, folderServerId)
        accounts.notifyFolderChanged(account, folderId)
    }

    @Suppress("TooGenericExceptionThrown")
    private fun loadMessage(folderServerId: String, messageServerId: String): LocalMessage {
        try {
            val localFolder = localStore.getFolder(folderServerId)
            localFolder.open()
            return localFolder.getMessage(messageServerId)
        } catch (e: MessagingException) {
            throw RuntimeException("Couldn't load message ($folderServerId:$messageServerId)", e)
        }
    }
}
