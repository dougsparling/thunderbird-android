package net.thunderbird.feature.mail.sync.internal.legacy

import android.content.Context
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import com.fsck.k9.controller.MessagingController
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.MailSynchronizer
import net.thunderbird.feature.mail.sync.api.SyncEvent

internal class ControllerMailSynchronizer(
    private val controller: MessagingController,
    private val accounts: LegacyAccounts,
    private val ioDispatcher: CoroutineDispatcher,
) : MailSynchronizer {
    override fun requestFolderSync(accountId: AccountId, folderId: Long, notify: Boolean) {
        controller.synchronizeMailbox(accounts.get(accountId), folderId, notify, null)
    }

    override fun requestMoreMessages(accountId: AccountId, folderId: Long) {
        controller.loadMoreMessages(accounts.get(accountId), folderId)
    }

    override fun requestFolderListRefresh(accountId: AccountId) {
        controller.refreshFolderList(accounts.get(accountId))
    }

    override suspend fun refreshFolderList(accountId: AccountId) {
        val account = accounts.get(accountId)
        runInterruptible(ioDispatcher) { controller.refreshFolderListBlocking(account) }
    }

    override fun requestCheckMail(
        accountId: AccountId?,
        ignoreLastCheckedTime: Boolean,
        useManualWakeLock: Boolean,
        notify: Boolean,
    ) {
        val account = accountId?.let(accounts::find)
        controller.checkMail(account, ignoreLastCheckedTime, useManualWakeLock, notify, null)
    }

    override suspend fun checkMail(
        accountId: AccountId?,
        ignoreLastCheckedTime: Boolean,
        useManualWakeLock: Boolean,
        notify: Boolean,
    ) {
        val account = accountId?.let(accounts::find)
        suspendCancellableCoroutine { continuation ->
            val listener = object : SimpleMessagingListener() {
                override fun checkMailFinished(context: Context?, account: LegacyAccountDto?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            controller.checkMail(account, ignoreLastCheckedTime, useManualWakeLock, notify, listener)
        }
    }

    override suspend fun syncPeriodically(accountId: AccountId): Boolean {
        val account = accounts.get(accountId)
        return runInterruptible(ioDispatcher) { controller.performPeriodicMailSync(account) }
    }

    override suspend fun syncPushedFolder(accountId: AccountId, folderServerId: String) {
        val account = accounts.get(accountId)
        runInterruptible(ioDispatcher) { controller.synchronizeMailboxBlocking(account, folderServerId) }
    }

    override fun reportError(accountId: AccountId, exception: Exception) {
        controller.handleException(accounts.get(accountId), exception)
    }

    override fun checkAuthenticationProblem(accountId: AccountId) {
        controller.checkAuthenticationProblem(accounts.get(accountId))
    }

    override fun observeEvents(): Flow<SyncEvent> = callbackFlow {
        val listener = SyncEventListener { event -> trySend(event) }
        // Registering replays the state of folder syncs to the listener, then live events follow.
        controller.addListener(listener)
        awaitClose { controller.removeListener(listener) }
    }.buffer(Channel.UNLIMITED)
}

/** Turns the controller's sync callbacks into [SyncEvent]s. */
private class SyncEventListener(private val emit: (SyncEvent) -> Unit) : SimpleMessagingListener() {
    override fun synchronizeMailboxStarted(account: LegacyAccountDto, folderId: Long) {
        emit(SyncEvent.FolderSyncStarted(account.id, folderId))
    }

    override fun synchronizeMailboxHeadersProgress(
        account: LegacyAccountDto,
        folderServerId: String,
        completed: Int,
        total: Int,
    ) {
        emit(SyncEvent.FolderHeadersProgress(account.id, folderServerId, completed, total))
    }

    override fun synchronizeMailboxHeadersFinished(
        account: LegacyAccountDto,
        folderServerId: String,
        totalMessagesInMailbox: Int,
        numNewMessages: Int,
    ) {
        emit(SyncEvent.FolderHeadersFinished(account.id, folderServerId, totalMessagesInMailbox, numNewMessages))
    }

    override fun synchronizeMailboxProgress(account: LegacyAccountDto, folderId: Long, completed: Int, total: Int) {
        emit(SyncEvent.FolderSyncProgress(account.id, folderId, completed, total))
    }

    override fun synchronizeMailboxFinished(account: LegacyAccountDto, folderId: Long) {
        emit(SyncEvent.FolderSyncFinished(account.id, folderId))
    }

    override fun synchronizeMailboxFailed(account: LegacyAccountDto, folderId: Long, message: String?) {
        emit(SyncEvent.FolderSyncFailed(account.id, folderId, message.orEmpty()))
    }

    override fun checkMailStarted(context: Context?, account: LegacyAccountDto?) {
        emit(SyncEvent.CheckMailStarted(account?.id))
    }

    override fun checkMailFinished(context: Context?, account: LegacyAccountDto?) {
        emit(SyncEvent.CheckMailFinished(account?.id))
    }

    override fun messageUidChanged(account: LegacyAccountDto, folderId: Long, oldUid: String, newUid: String) {
        emit(SyncEvent.MessageUidChanged(account.id, folderId, oldUid, newUid))
    }
}
