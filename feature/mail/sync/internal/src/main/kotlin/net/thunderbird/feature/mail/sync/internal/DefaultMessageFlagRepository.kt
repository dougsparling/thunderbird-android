package net.thunderbird.feature.mail.sync.internal

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.controller.PendingMarkAllAsRead
import com.fsck.k9.controller.PendingSetFlag
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.mailstore.LocalMessageReader
import com.fsck.k9.notification.NotificationController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.MessageFlagRepository
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import net.thunderbird.feature.mail.sync.internal.engine.WorkPriority

private const val TAG = "MessageFlagRepository"

@Suppress("LongParameterList")
internal class DefaultMessageFlagRepository(
    private val accounts: AccountStores,
    private val localMessages: LocalMessages,
    private val pendingCommands: PendingCommandQueue,
    private val serializer: RemoteWorkSerializer,
    private val notificationController: NotificationController,
    private val localMessageReader: LocalMessageReader,
    private val appScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: Logger,
) : MessageFlagRepository {
    override suspend fun update(accountId: AccountId, messageIds: List<Long>, flag: Flag, newState: Boolean) {
        val account = accounts.get(accountId)
        localMessages.setFlagInCache(account, messageIds, flag, newState)

        serializer.enqueue("setFlag", WorkPriority.BACKGROUND) {
            setFlagBlocking(account, messageIds, flag, newState, threadedList = false)
        }
    }

    override suspend fun updateThreads(accountId: AccountId, threadRootIds: List<Long>, flag: Flag, newState: Boolean) {
        val account = accounts.get(accountId)
        localMessages.setFlagForThreadsInCache(account, threadRootIds, flag, newState)

        serializer.enqueue("setFlagForThreads", WorkPriority.BACKGROUND) {
            setFlagBlocking(account, threadRootIds, flag, newState, threadedList = true)
        }
    }

    @Suppress("TooGenericExceptionThrown")
    override suspend fun update(message: MessageReference, flag: Flag, newState: Boolean) {
        val account = accounts.get(message)
        val folderId = message.folderId
        try {
            val localFolder = accounts.localStore(account).getFolder(folderId)
            localFolder.open()

            val localMessage = localFolder.getMessage(message.uid) ?: return
            val messages = listOf(localMessage)

            // Update the messages in the local store
            localFolder.setFlags(messages, setOf(flag), newState)

            accounts.notifyFolderChanged(account, folderId)

            // Handle the remote side
            if (accounts.backend(account).supportsFlags && !localFolder.isLocalOnly) {
                val uids = messages.map { it.uid }
                queueSetFlag(account, folderId, newState, flag, uids)
                pendingCommands.processInBackground(account)
            }
        } catch (e: MessagingException) {
            throw RuntimeException(e)
        }
    }

    override suspend fun markAllAsRead(accountId: AccountId, folderId: Long) {
        val account = accounts.get(accountId)
        pendingCommands.add(account, PendingMarkAllAsRead.create(folderId))
        pendingCommands.processInBackground(account)
    }

    override suspend fun markAsOpened(message: MessageReference): Boolean {
        val account = accounts.get(message)
        val localMessage = localMessageReader.loadMessageMetadata(account, message.folderId, message.uid)
        val markedAsRead = account.isMarkMessageAsReadOnView && !localMessage.isSet(Flag.SEEN)
        markMessageAsOpened(account, localMessage)
        return markedAsRead
    }

    override suspend fun clearNewMessages(accountId: AccountId) {
        val account = accounts.get(accountId)
        serializer.enqueue("clearNewMessages", WorkPriority.FOREGROUND) {
            accounts.messageStore(account).clearNewMessageState()
        }
    }

    private fun markMessageAsOpened(account: LegacyAccountDto, message: LocalMessage) {
        appScope.launch(ioDispatcher) {
            notificationController.removeNewMailNotification(account, message.makeMessageReference())
        }

        if (message.isSet(Flag.SEEN)) {
            // Nothing to do if the message is already marked as read
            return
        }

        val markMessageAsRead = account.isMarkMessageAsReadOnView
        if (markMessageAsRead) {
            // Mark the message itself as read right away
            try {
                message.setFlagInternal(Flag.SEEN, true)
            } catch (e: MessagingException) {
                logger.error(TAG, e) { "Error while marking message as read" }
            }

            // Also mark the message as read in the cache
            localMessages.setFlagInCache(account, listOf(message.databaseId), Flag.SEEN, true)
        }

        serializer.enqueue("markMessageAsOpened", WorkPriority.BACKGROUND) {
            if (markMessageAsRead) {
                setFlagBlocking(account, listOf(message.databaseId), Flag.SEEN, newState = true, threadedList = false)
            } else {
                // Marking a message as read will automatically mark it as "not new". But if we don't mark the message
                // as read on opening, we have to manually mark it as "not new".
                markMessageAsNotNew(account, message)
            }
        }
    }

    private fun markMessageAsNotNew(account: LegacyAccountDto, message: LocalMessage) {
        val messageStore = accounts.messageStore(account)
        val folderId = message.folder.databaseId
        messageStore.setNewMessageState(folderId, message.uid, false)
    }

    @Suppress("ReturnCount")
    private fun setFlagBlocking(
        account: LegacyAccountDto,
        ids: List<Long>,
        flag: Flag,
        newState: Boolean,
        threadedList: Boolean,
    ) {
        val localStore = try {
            accounts.localStore(account)
        } catch (e: MessagingException) {
            logger.error(TAG, e) { "Couldn't get LocalStore instance" }
            return
        }

        // Update affected messages in the database. This should be as fast as possible so the UI can be updated with
        // the new state.
        try {
            if (threadedList) {
                localStore.setFlagForThreads(ids, flag, newState)
                localMessages.removeFlagForThreadsFromCache(account, ids, flag)
            } else {
                localStore.setFlag(ids, flag, newState)
                localMessages.removeFlagFromCache(account, ids, flag)
            }
        } catch (e: MessagingException) {
            logger.error(TAG, e) { "Couldn't set flags in local database" }
        }

        // Read folder ID and UID of messages from the database
        val folderMap = try {
            localStore.getFolderIdsAndUids(ids, threadedList)
        } catch (e: MessagingException) {
            logger.error(TAG, e) { "Couldn't get folder name and UID of messages" }
            return
        }

        val accountSupportsFlags = accounts.backend(account).supportsFlags

        // Loop over all folders
        for ((folderId, uids) in folderMap) {
            // Notify listeners of changed folder status
            accounts.notifyFolderChanged(account, folderId)

            if (flag == Flag.SEEN && newState) {
                cancelNotificationsForMessages(account, folderId, uids)
            }

            if (accountSupportsFlags) {
                val localFolder = localStore.getFolder(folderId)
                try {
                    localFolder.open()
                    if (!localFolder.isLocalOnly) {
                        // Send flag change to server
                        queueSetFlag(account, folderId, newState, flag, uids)
                        pendingCommands.processInBackground(account)
                    }
                } catch (e: MessagingException) {
                    logger.error(TAG, e) { "Couldn't open folder. Account: $account, folder ID: $folderId" }
                }
            }
        }
    }

    private fun cancelNotificationsForMessages(account: LegacyAccountDto, folderId: Long, uids: List<String>) {
        for (uid in uids) {
            val messageReference = MessageReference(account.uuid, folderId, uid)
            notificationController.removeNewMailNotification(account, messageReference)
        }
    }

    private fun queueSetFlag(
        account: LegacyAccountDto,
        folderId: Long,
        newState: Boolean,
        flag: Flag,
        uids: List<String>,
    ) {
        pendingCommands.add(account, PendingSetFlag.create(folderId, newState, flag, uids))
    }
}
