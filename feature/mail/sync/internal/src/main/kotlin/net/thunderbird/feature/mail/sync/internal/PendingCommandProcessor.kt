package net.thunderbird.feature.mail.sync.internal

import com.fsck.k9.backend.api.Backend
import com.fsck.k9.controller.PendingAppend
import com.fsck.k9.controller.PendingCommand
import com.fsck.k9.controller.PendingDelete
import com.fsck.k9.controller.PendingEmptySpam
import com.fsck.k9.controller.PendingEmptyTrash
import com.fsck.k9.controller.PendingExpunge
import com.fsck.k9.controller.PendingMarkAllAsRead
import com.fsck.k9.controller.PendingMoveAndMarkAsRead
import com.fsck.k9.controller.PendingMoveOrCopy
import com.fsck.k9.controller.PendingReplace
import com.fsck.k9.controller.PendingSetFlag
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mailstore.LocalFolder
import com.fsck.k9.mailstore.LocalMessage
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.mail.message.list.LocalMessageUidPrefixProvider
import net.thunderbird.feature.mail.sync.api.SyncEvent
import net.thunderbird.feature.mail.sync.internal.engine.PendingCommandExecutor
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import net.thunderbird.feature.mail.sync.internal.engine.WorkPriority

private const val TAG = "PendingCommandProcessor"

/** Carries out pending commands against the server and updates the local copies to match. */
@Suppress("TooManyFunctions")
internal class PendingCommandProcessor(
    private val accounts: AccountStores,
    private val events: SyncEventBus,
    private val serializer: RemoteWorkSerializer,
    private val localMessageUidPrefixProvider: LocalMessageUidPrefixProvider,
    private val logger: Logger,
    private val isDebug: Boolean,
) : PendingCommandExecutor {
    @Throws(MessagingException::class)
    override fun execute(command: PendingCommand, account: LegacyAccountDto) {
        when (command) {
            is PendingAppend -> processPendingAppend(command, account)
            is PendingReplace -> processPendingReplace(command, account)
            is PendingMarkAllAsRead -> processPendingMarkAllAsRead(command, account)
            is PendingSetFlag -> processPendingSetFlag(command, account)
            is PendingDelete -> processPendingDelete(command, account)
            is PendingExpunge -> processPendingExpunge(command, account)
            is PendingMoveOrCopy -> processPendingMoveOrCopy(command, account)
            is PendingMoveAndMarkAsRead -> processPendingMoveAndRead(command, account)
            is PendingEmptySpam -> processPendingEmptySpam(account)
            is PendingEmptyTrash -> processPendingEmptyTrash(account)
        }
    }

    /**
     * Uploads a local message to the server, first checking that it wasn't uploaded already. The local copy then gets
     * the server's ID, so the next sync doesn't download a second copy.
     */
    @Suppress("ReturnCount")
    private fun processPendingAppend(command: PendingAppend, account: LegacyAccountDto) {
        val localStore = accounts.localStore(account)
        val folderId = command.folderId
        val localFolder = localStore.getFolder(folderId)
        localFolder.open()

        val folderServerId = localFolder.serverId
        val localMessage = localFolder.getMessage(command.uid) ?: return

        if (!localMessage.uid.startsWith(localMessageUidPrefixProvider.get())) {
            // TODO: This should never happen. Throw in debug builds.
            return
        }

        val backend = accounts.backend(account)

        if (localMessage.isSet(Flag.X_REMOTE_COPY_STARTED)) {
            logger.warn(TAG) {
                "Local message with uid ${localMessage.uid} has flag ${Flag.X_REMOTE_COPY_STARTED} already set, " +
                    "checking for remote message with same message id"
            }

            val messageServerId = backend.findByMessageId(folderServerId, localMessage.messageId)
            if (messageServerId != null) {
                logger.warn(TAG) {
                    "Local message has flag ${Flag.X_REMOTE_COPY_STARTED} already set, and there is a remote " +
                        "message with uid $messageServerId, assuming message was already copied and aborting this copy"
                }

                val oldUid = localMessage.uid
                localMessage.uid = messageServerId
                localFolder.changeUid(localMessage)

                events.emit(SyncEvent.MessageUidChanged(account.id, folderId, oldUid, localMessage.uid))
                return
            } else {
                logger.warn(TAG) { "No remote message with message-id found, proceeding with append" }
            }
        }

        // If the message does not exist remotely we just upload it and then update our local copy with the new uid.
        val fetchProfile = FetchProfile().apply { add(FetchProfile.Item.BODY) }
        localFolder.fetch(listOf(localMessage), fetchProfile, null)
        val oldUid = localMessage.uid
        localMessage.setFlag(Flag.X_REMOTE_COPY_STARTED, true)

        val messageServerId = backend.uploadMessage(folderServerId, localMessage)

        if (messageServerId == null) {
            // We didn't get the server UID of the uploaded message. Remove the local message now. The uploaded
            // version will be downloaded during the next sync.
            localFolder.destroyMessages(listOf(localMessage))
        } else {
            localMessage.uid = messageServerId
            localFolder.changeUid(localMessage)

            events.emit(SyncEvent.MessageUidChanged(account.id, folderId, oldUid, localMessage.uid))
        }
    }

    /** Uploads a new version of a draft and deletes the old one from the server. */
    private fun processPendingReplace(command: PendingReplace, account: LegacyAccountDto) {
        val localStore = accounts.localStoreOrThrow(account)
        val localFolder = localStore.getFolder(command.folderId)
        localFolder.open()

        val backend = accounts.backend(account)

        val uploadMessageId = command.uploadMessageId
        val localMessage = localFolder.getMessage(uploadMessageId)
        if (localMessage == null) {
            logger.warn(TAG) { "Couldn't find local copy of message to upload [ID: $uploadMessageId]" }
            return
        } else if (!localMessage.uid.startsWith(localMessageUidPrefixProvider.get())) {
            logger.info(TAG) {
                "Message [ID: $uploadMessageId] to be uploaded already has a server ID set. Skipping upload."
            }
        } else {
            uploadMessage(backend, account, localFolder, localMessage)
        }

        deleteMessage(backend, localFolder, command.deleteMessageId)
    }

    private fun uploadMessage(
        backend: Backend,
        account: LegacyAccountDto,
        localFolder: LocalFolder,
        localMessage: LocalMessage,
    ) {
        val folderServerId = localFolder.serverId
        logger.debug(TAG) { "Uploading message [ID: ${localMessage.databaseId}] to remote folder '$folderServerId'" }

        val fetchProfile = FetchProfile().apply { add(FetchProfile.Item.BODY) }
        localFolder.fetch(listOf(localMessage), fetchProfile, null)

        val messageServerId = backend.uploadMessage(folderServerId, localMessage)

        if (messageServerId == null) {
            logger.warn(TAG) {
                "Failed to get a server ID for the uploaded message. " +
                    "Removing local copy [ID: ${localMessage.databaseId}]"
            }
            localMessage.destroy()
        } else {
            val oldUid = localMessage.uid

            localMessage.uid = messageServerId
            localFolder.changeUid(localMessage)

            events.emit(SyncEvent.MessageUidChanged(account.id, localFolder.databaseId, oldUid, localMessage.uid))
        }
    }

    private fun deleteMessage(backend: Backend, localFolder: LocalFolder, messageId: Long) {
        val messageServerId = localFolder.getMessageUidById(messageId) ?: run {
            logger.info(TAG) { "Couldn't find local copy of message [ID: $messageId] to be deleted. Skipping delete." }
            return
        }

        val messageServerIds = listOf(messageServerId)
        val folderServerId = localFolder.serverId
        backend.deleteMessages(folderServerId, messageServerIds)

        destroyPlaceholderMessages(localFolder, messageServerIds)
    }

    private fun processPendingMoveOrCopy(command: PendingMoveOrCopy, account: LegacyAccountDto) {
        val operation = if (command.isCopy) MoveOrCopyFlavor.COPY else MoveOrCopyFlavor.MOVE
        val newUidMap = command.newUidMap
        val uids = newUidMap?.keys?.toList() ?: command.uids

        processPendingMoveOrCopy(account, command.srcFolderId, command.destFolderId, uids, operation, newUidMap)
    }

    private fun processPendingMoveAndRead(command: PendingMoveAndMarkAsRead, account: LegacyAccountDto) {
        val newUidMap = command.newUidMap
        val uids = newUidMap.keys.toList()

        processPendingMoveOrCopy(
            account,
            command.srcFolderId,
            command.destFolderId,
            uids,
            MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ,
            newUidMap,
        )
    }

    @Suppress("LongParameterList")
    private fun processPendingMoveOrCopy(
        account: LegacyAccountDto,
        srcFolderId: Long,
        destFolderId: Long,
        uids: List<String>?,
        operation: MoveOrCopyFlavor,
        newUidMap: Map<String, String>?,
    ) {
        requireNotNull(newUidMap)
        requireNotNull(uids)

        val localStore = accounts.localStore(account)

        val localSourceFolder = localStore.getFolder(srcFolderId)
        localSourceFolder.open()
        val srcFolderServerId = localSourceFolder.serverId

        val localDestFolder = localStore.getFolder(destFolderId)
        localDestFolder.open()
        val destFolderServerId = localDestFolder.serverId

        val backend = accounts.backend(account)

        val remoteUidMap = when (operation) {
            MoveOrCopyFlavor.COPY -> backend.copyMessages(srcFolderServerId, destFolderServerId, uids)

            MoveOrCopyFlavor.MOVE -> backend.moveMessages(srcFolderServerId, destFolderServerId, uids)

            MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ -> {
                backend.moveMessagesAndMarkAsRead(srcFolderServerId, destFolderServerId, uids)
            }
        }

        if (operation != MoveOrCopyFlavor.COPY) {
            destroyPlaceholderMessages(localSourceFolder, uids)
        }

        // TODO: Change Backend interface to ensure we never receive null for remoteUidMap
        val serverUids = remoteUidMap.orEmpty()

        // Update local messages (that currently have local UIDs) with new server IDs
        for (uid in uids) {
            val localUid = newUidMap[uid]
            val newUid = serverUids[uid]

            // Local message no longer exists
            val localMessage = localDestFolder.getMessage(localUid) ?: continue

            if (newUid != null) {
                // Update local message with new server ID
                localMessage.uid = newUid
                localDestFolder.changeUid(localMessage)
                events.emit(SyncEvent.MessageUidChanged(account.id, destFolderId, localUid.orEmpty(), newUid))
            } else {
                // New server ID wasn't provided. Remove local message.
                localMessage.destroy()
            }
        }
    }

    /** Removes the local copies left in place of messages that were moved or deleted, once the server has done it. */
    private fun destroyPlaceholderMessages(localFolder: LocalFolder, uids: List<String>) {
        for (uid in uids) {
            val placeholderMessage = localFolder.getMessage(uid) ?: continue

            if (placeholderMessage.isSet(Flag.DELETED)) {
                placeholderMessage.destroy()
            } else {
                logger.warn(TAG) {
                    "Expected local message $uid in folder ${localFolder.serverId} to be a placeholder, " +
                        "but DELETE flag wasn't set"
                }

                if (isDebug) {
                    throw AssertionError("Placeholder message must have the DELETED flag set")
                }
            }
        }
    }

    private fun processPendingSetFlag(command: PendingSetFlag, account: LegacyAccountDto) {
        val backend = accounts.backend(account)
        val folderServerId = accounts.folderServerId(account, command.folderId)
        backend.setFlag(folderServerId, command.uids, command.flag, command.newState)
    }

    private fun processPendingDelete(command: PendingDelete, account: LegacyAccountDto) {
        val folderId = command.folderId
        val uids = command.uids

        val backend = accounts.backend(account)
        val folderServerId = accounts.folderServerId(account, folderId)
        backend.deleteMessages(folderServerId, uids)

        val localFolder = accounts.localStore(account).getFolder(folderId)
        localFolder.open()
        destroyPlaceholderMessages(localFolder, uids)
    }

    private fun processPendingExpunge(command: PendingExpunge, account: LegacyAccountDto) {
        val backend = accounts.backend(account)
        val folderServerId = accounts.folderServerId(account, command.folderId)
        backend.expunge(folderServerId)
    }

    private fun processPendingMarkAllAsRead(command: PendingMarkAllAsRead, account: LegacyAccountDto) {
        val localFolder = accounts.localStore(account).getFolder(command.folderId)

        localFolder.open()
        val folderServerId = localFolder.serverId

        logger.info(TAG) { "Marking all messages in $account:$folderServerId as read" }

        // TODO: Make this one database UPDATE operation
        val messages = localFolder.getMessages(false)
        for (message in messages) {
            if (!message.isSet(Flag.SEEN)) {
                message.setFlag(Flag.SEEN, true)
            }
        }

        accounts.notifyFolderChanged(account, command.folderId)

        val backend = accounts.backend(account)
        if (backend.supportsFlags) {
            backend.markAllAsRead(folderServerId)
        }
    }

    private fun processPendingEmptySpam(account: LegacyAccountDto) {
        val spamFolderId = account.spamFolderId ?: return
        deleteAllMessages(account, spamFolderId)
    }

    private fun processPendingEmptyTrash(account: LegacyAccountDto) {
        val trashFolderId = account.trashFolderId ?: return
        deleteAllMessages(account, trashFolderId)
    }

    private fun deleteAllMessages(account: LegacyAccountDto, folderId: Long) {
        val folder = accounts.localStore(account).getFolder(folderId)
        folder.open()
        val folderServerId = folder.serverId

        val backend = accounts.backend(account)
        backend.deleteAllMessages(folderServerId)

        // Remove all messages marked as deleted
        folder.destroyDeletedMessages()

        compact(account)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun compact(account: LegacyAccountDto) {
        serializer.enqueue("compact:$account", WorkPriority.BACKGROUND) {
            try {
                accounts.messageStore(account).compact()
            } catch (e: Exception) {
                logger.error(TAG, e) { "Failed to compact account $account" }
            }
        }
    }
}
