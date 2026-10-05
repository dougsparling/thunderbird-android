package net.thunderbird.feature.mail.sync.internal

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.controller.PendingAppend
import com.fsck.k9.controller.PendingDelete
import com.fsck.k9.controller.PendingEmptySpam
import com.fsck.k9.controller.PendingEmptyTrash
import com.fsck.k9.controller.PendingExpunge
import com.fsck.k9.controller.PendingSetFlag
import com.fsck.k9.mailstore.LocalFolder
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.notification.NotificationController
import net.thunderbird.core.android.account.DeletePolicy
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.OutboxFolderManager
import net.thunderbird.feature.mail.message.list.LocalDeleteOperationDecider
import net.thunderbird.feature.mail.message.list.LocalMessageUidPrefixProvider
import net.thunderbird.feature.mail.sync.api.MessageDeleteRepository
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import net.thunderbird.feature.mail.sync.internal.engine.WorkPriority

private const val TAG = "MessageDeleteRepository"

@Suppress("LongParameterList", "TooManyFunctions")
internal class DefaultMessageDeleteRepository(
    private val accounts: AccountStores,
    private val localMessages: LocalMessages,
    private val messageMover: MessageMover,
    private val pendingCommands: PendingCommandQueue,
    private val serializer: RemoteWorkSerializer,
    private val notificationController: NotificationController,
    private val localDeleteOperationDecider: LocalDeleteOperationDecider,
    private val outboxFolderManager: OutboxFolderManager,
    private val localMessageUidPrefixProvider: LocalMessageUidPrefixProvider,
    private val logger: Logger,
) : MessageDeleteRepository {
    override suspend fun delete(messages: List<MessageReference>) {
        deleteMessages(messages, skipTrashFolder = false)
    }

    override suspend fun deleteThreads(messages: List<MessageReference>) {
        localMessages.forEachFolder(messages) { account, messageFolder, accountMessages ->
            localMessages.hide(account, accountMessages)
            serializer.enqueue("deleteThreads", WorkPriority.BACKGROUND) {
                deleteThreadsBlocking(account, messageFolder.databaseId, accountMessages)
            }
        }
    }

    override suspend fun expunge(accountId: AccountId, folderId: Long) {
        val account = accounts.get(accountId)
        serializer.enqueue("expunge", WorkPriority.BACKGROUND) {
            pendingCommands.add(account, PendingExpunge.create(folderId))
            pendingCommands.processInBackground(account)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun emptyTrash(accountId: AccountId) {
        val account = accounts.get(accountId)
        serializer.enqueue("emptyTrash", WorkPriority.BACKGROUND) {
            try {
                val trashFolderId = account.trashFolderId
                if (trashFolderId == null) {
                    logger.warn(TAG) { "No Trash folder configured. Can't empty trash." }
                    return@enqueue
                }

                val localFolder = accounts.localStore(account).getFolder(trashFolderId)
                localFolder.open()

                val isTrashLocalOnly = isTrashLocalOnly(account)
                if (isTrashLocalOnly) {
                    localFolder.clearAllMessages()
                } else {
                    localFolder.destroyLocalOnlyMessages()
                    localFolder.setFlags(setOf(Flag.DELETED), true)
                }

                accounts.notifyFolderChanged(account, trashFolderId)

                if (!isTrashLocalOnly) {
                    pendingCommands.add(account, PendingEmptyTrash.create())
                    pendingCommands.processInBackground(account)
                }
            } catch (e: Exception) {
                logger.error(TAG, e) { "emptyTrash failed" }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun emptySpam(accountId: AccountId) {
        val account = accounts.get(accountId)
        serializer.enqueue("emptySpam", WorkPriority.BACKGROUND) {
            try {
                val spamFolderId = account.spamFolderId
                if (spamFolderId == null) {
                    logger.warn(TAG) { "No Spam folder configured. Can't empty spam." }
                    return@enqueue
                }

                val localFolder = accounts.localStore(account).getFolder(spamFolderId)
                localFolder.open()

                localFolder.destroyLocalOnlyMessages()
                localFolder.setFlags(setOf(Flag.DELETED), true)

                accounts.notifyFolderChanged(account, spamFolderId)

                pendingCommands.add(account, PendingEmptySpam.create())
                pendingCommands.processInBackground(account)
            } catch (e: Exception) {
                logger.error(TAG, e) { "emptySpam failed" }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun clearLocalMessages(accountId: AccountId, folderId: Long) {
        val account = accounts.get(accountId)
        serializer.enqueue("clearFolder", WorkPriority.BACKGROUND) {
            try {
                val localFolder = accounts.localStore(account).getFolder(folderId)
                localFolder.open()
                localFolder.clearAllMessages()
            } catch (e: Exception) {
                logger.error(TAG, e) { "clearFolder failed" }
            }
        }
    }

    /** Like [delete]; with [skipTrashFolder] the messages are removed without keeping a copy in the trash folder. */
    fun deleteMessages(messages: List<MessageReference>, skipTrashFolder: Boolean) {
        localMessages.forEachFolder(messages) { account, messageFolder, accountMessages ->
            localMessages.hide(account, accountMessages)
            serializer.enqueue("deleteMessages", WorkPriority.BACKGROUND) {
                deleteMessagesBlocking(account, messageFolder.databaseId, accountMessages, skipTrashFolder)
            }
        }
    }

    private fun deleteThreadsBlocking(account: LegacyAccountDto, folderId: Long, messages: List<LocalMessage>) {
        try {
            val messagesToDelete = localMessages.collectMessagesInThreads(account, messages)
            deleteMessagesBlocking(account, folderId, messagesToDelete, skipTrashFolder = false)
        } catch (e: MessagingException) {
            logger.error(TAG, e) { "Something went wrong while deleting threads" }
        }
    }

    @Suppress("TooGenericExceptionThrown", "LongMethod", "CyclomaticComplexMethod")
    private fun deleteMessagesBlocking(
        account: LegacyAccountDto,
        folderId: Long,
        messages: List<LocalMessage>,
        skipTrashFolder: Boolean,
    ) {
        try {
            val localOnlyMessages = mutableListOf<LocalMessage>()
            val syncedMessages = mutableListOf<LocalMessage>()
            val syncedMessageUids = mutableListOf<String>()
            for (message in messages) {
                notificationController.removeNewMailNotification(account, message.makeMessageReference())

                val uid = message.uid
                if (uid.startsWith(localMessageUidPrefixProvider.get())) {
                    localOnlyMessages.add(message)
                } else {
                    syncedMessages.add(message)
                    syncedMessageUids.add(uid)
                }
            }

            val backend = accounts.backend(account)

            val localStore = accounts.localStore(account)
            val localFolder = localStore.getFolder(folderId)
            localFolder.open()

            var uidMap: Map<String, String>? = null
            val trashFolderId = account.trashFolderId
            val doNotMoveToTrashFolder = skipTrashFolder ||
                localDeleteOperationDecider.isDeleteImmediately(account, folderId)

            var localTrashFolder: LocalFolder? = null
            if (doNotMoveToTrashFolder) {
                logger.debug(TAG) { "Not moving deleted messages to local Trash folder. Removing local copies." }

                if (localOnlyMessages.isNotEmpty()) {
                    localFolder.destroyMessages(localOnlyMessages)
                }
                if (syncedMessages.isNotEmpty()) {
                    localFolder.setFlags(syncedMessages, setOf(Flag.DELETED), true)
                }
            } else {
                logger.debug(TAG) { "Deleting messages in normal folder, moving" }
                checkNotNull(trashFolderId)
                localTrashFolder = localStore.getFolder(trashFolderId)

                val messageStore = accounts.messageStore(account)

                val messageIds = messages.map { it.databaseId }
                val messageIdToUidMapping = messages.associate { it.databaseId to it.uid }

                val moveMessageIdMapping = messageStore.moveMessages(messageIds, trashFolderId)
                uidMap = messageMover.createUidMap(account, moveMessageIdMapping, messageIdToUidMapping)

                if (account.isMarkMessageAsReadOnDelete) {
                    val destinationMessageIds = moveMessageIdMapping.values
                    messageStore.setFlag(destinationMessageIds, Flag.SEEN, true)
                }
            }

            accounts.notifyFolderChanged(account, folderId)
            if (localTrashFolder != null) {
                accounts.notifyFolderChanged(account, checkNotNull(trashFolderId))
            }

            logger.debug(TAG) { "Delete policy for account $account is ${account.deletePolicy}" }

            val outboxFolderId = outboxFolderManager.getOutboxFolderIdSync(account.id, createIfMissing = true)

            if (outboxFolderId != -1L && folderId == outboxFolderId && backend.supportsUpload) {
                // If the message was in the Outbox, then it has been copied to local Trash, and has to be copied to
                // remote trash
                for (destinationUid in checkNotNull(uidMap).values) {
                    pendingCommands.add(account, PendingAppend.create(checkNotNull(trashFolderId), destinationUid))
                }
                pendingCommands.processInBackground(account)
            } else if (localFolder.isLocalOnly) {
                // Nothing to do on the remote side
            } else if (syncedMessageUids.isNotEmpty()) {
                when (account.deletePolicy) {
                    DeletePolicy.ON_DELETE -> {
                        if (doNotMoveToTrashFolder || !backend.supportsTrashFolder) {
                            pendingCommands.add(account, PendingDelete.create(folderId, syncedMessageUids))
                        } else if (account.isMarkMessageAsReadOnDelete) {
                            messageMover.queueMoveOrCopy(
                                account,
                                folderId,
                                checkNotNull(trashFolderId),
                                MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ,
                                checkNotNull(uidMap),
                            )
                        } else {
                            messageMover.queueMoveOrCopy(
                                account,
                                folderId,
                                checkNotNull(trashFolderId),
                                MoveOrCopyFlavor.MOVE,
                                checkNotNull(uidMap),
                            )
                        }
                        pendingCommands.processInBackground(account)
                    }

                    DeletePolicy.MARK_AS_READ -> {
                        val command = PendingSetFlag.create(localFolder.databaseId, true, Flag.SEEN, syncedMessageUids)
                        pendingCommands.add(account, command)
                        pendingCommands.processInBackground(account)
                    }

                    else -> {
                        logger.debug(TAG) { "Delete policy ${account.deletePolicy} prevents delete from server" }
                    }
                }
            }

            localMessages.unhide(account, messages)
        } catch (e: MessagingException) {
            throw RuntimeException("Error deleting message from local store.", e)
        }
    }

    /**
     * Whether the account only has a local trash folder that isn't synced with a folder on the server. Currently this
     * is only the case for POP3 accounts.
     */
    private fun isTrashLocalOnly(account: LegacyAccountDto): Boolean {
        return !accounts.backend(account).supportsTrashFolder
    }
}
