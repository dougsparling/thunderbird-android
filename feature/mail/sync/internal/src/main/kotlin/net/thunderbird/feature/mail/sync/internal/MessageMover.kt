package net.thunderbird.feature.mail.sync.internal

import com.fsck.k9.controller.PendingMoveAndMarkAsRead
import com.fsck.k9.controller.PendingMoveOrCopy
import com.fsck.k9.mailstore.LocalMessage
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.mail.message.list.LocalMessageUidPrefixProvider

private const val TAG = "MessageMover"

internal enum class MoveOrCopyFlavor {
    MOVE,
    COPY,
    MOVE_AND_MARK_AS_READ,
}

/** Moves or copies messages locally and queues the same change for the server. */
internal class MessageMover(
    private val accounts: AccountStores,
    private val localMessages: LocalMessages,
    private val pendingCommands: PendingCommandQueue,
    private val localMessageUidPrefixProvider: LocalMessageUidPrefixProvider,
    private val logger: Logger,
) {
    @Suppress("TooGenericExceptionThrown", "LongMethod", "CyclomaticComplexMethod")
    fun moveOrCopy(
        account: LegacyAccountDto,
        srcFolderId: Long,
        inMessages: List<LocalMessage>,
        destFolderId: Long,
        operation: MoveOrCopyFlavor,
    ) {
        try {
            val localStore = accounts.localStore(account)
            val backend = accounts.backend(account)
            if (operation == MoveOrCopyFlavor.MOVE && !backend.supportsMove) {
                return
            }
            if (operation == MoveOrCopyFlavor.COPY && !backend.supportsCopy) {
                return
            }

            val localSrcFolder = localStore.getFolder(srcFolderId)
            localSrcFolder.open()

            val localDestFolder = localStore.getFolder(destFolderId)
            localDestFolder.open()

            var unreadCountAffected = false
            val uids = mutableListOf<String>()
            for (message in inMessages) {
                val uid = message.uid
                if (!uid.startsWith(localMessageUidPrefixProvider.get())) {
                    uids.add(uid)
                }

                if (operation == MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ) {
                    if (!message.isSet(Flag.SEEN)) {
                        unreadCountAffected = true
                        message.setFlag(Flag.SEEN, true)
                    }
                } else if (!unreadCountAffected && !message.isSet(Flag.SEEN)) {
                    unreadCountAffected = true
                }
            }

            val messages = localSrcFolder.getMessagesByUids(uids)
            if (messages.isNotEmpty()) {
                logger.info(TAG) {
                    "moveOrCopy: source folder = $srcFolderId, ${messages.size} messages, " +
                        "destination folder = $destFolderId, operation = ${operation.name}"
                }

                val messageStore = accounts.messageStore(account)

                val messageIds = messages.map { it.databaseId }
                val messageIdToUidMapping = messages.associate { it.databaseId to it.uid }

                val resultIdMapping: Map<Long, Long>
                if (operation == MoveOrCopyFlavor.COPY) {
                    resultIdMapping = messageStore.copyMessages(messageIds, destFolderId)

                    if (unreadCountAffected) {
                        // If this copy operation changes the unread count in the destination folder, notify the
                        // listeners.
                        accounts.notifyFolderChanged(account, destFolderId)
                    }
                } else {
                    resultIdMapping = messageStore.moveMessages(messageIds, destFolderId)

                    localMessages.unhide(account, messages)

                    if (unreadCountAffected) {
                        // If this move operation changes the unread count, notify the listeners that the unread count
                        // changed in both the source and destination folder.
                        accounts.notifyFolderChanged(account, srcFolderId)
                        accounts.notifyFolderChanged(account, destFolderId)
                    }
                }

                val uidMap = createUidMap(account, resultIdMapping, messageIdToUidMapping)
                queueMoveOrCopy(account, localSrcFolder.databaseId, localDestFolder.databaseId, operation, uidMap)
            }

            pendingCommands.processInBackground(account)
        } catch (e: MessagingException) {
            throw RuntimeException("Error moving message", e)
        }
    }

    /** Maps the source messages' server IDs to the (local) server IDs of their copies, see [moveOrCopy]. */
    fun createUidMap(
        account: LegacyAccountDto,
        resultIdMapping: Map<Long, Long>,
        messageIdToUidMapping: Map<Long, String>,
    ): Map<String, String> {
        val destinationMapping = accounts.messageStore(account).getMessageServerIds(resultIdMapping.values)

        val uidMap = mutableMapOf<String, String>()
        for ((sourceMessageId, destinationMessageId) in resultIdMapping) {
            val sourceUid = requireNotNull(messageIdToUidMapping[sourceMessageId])
            val destinationUid = requireNotNull(destinationMapping[destinationMessageId])
            uidMap[sourceUid] = destinationUid
        }
        return uidMap
    }

    fun queueMoveOrCopy(
        account: LegacyAccountDto,
        srcFolderId: Long,
        destFolderId: Long,
        operation: MoveOrCopyFlavor,
        uidMap: Map<String, String>,
    ) {
        val command = when (operation) {
            MoveOrCopyFlavor.MOVE -> PendingMoveOrCopy.create(srcFolderId, destFolderId, false, uidMap)
            MoveOrCopyFlavor.COPY -> PendingMoveOrCopy.create(srcFolderId, destFolderId, true, uidMap)
            MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ -> PendingMoveAndMarkAsRead.create(srcFolderId, destFolderId, uidMap)
        }
        pendingCommands.add(account, command)
    }
}
