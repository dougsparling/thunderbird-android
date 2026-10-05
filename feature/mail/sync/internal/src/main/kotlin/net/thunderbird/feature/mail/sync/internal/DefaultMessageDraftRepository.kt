package net.thunderbird.feature.mail.sync.internal

import app.k9mail.legacy.mailstore.SaveMessageData
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.controller.PendingAppend
import com.fsck.k9.controller.PendingReplace
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mailstore.SaveMessageDataCreator
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.MessageDraftRepository

private const val TAG = "MessageDraftRepository"

internal class DefaultMessageDraftRepository(
    private val accounts: AccountStores,
    private val pendingCommands: PendingCommandQueue,
    private val deleteRepository: DefaultMessageDeleteRepository,
    private val saveMessageDataCreator: SaveMessageDataCreator,
    private val logger: Logger,
) : MessageDraftRepository {
    override suspend fun save(
        accountId: AccountId,
        message: Message,
        existingDraftId: Long?,
        plaintextSubject: String?,
    ): Long? {
        return saveDraft(accounts.get(accountId), message, existingDraftId, plaintextSubject)
    }

    override suspend fun delete(accountId: AccountId, draftId: Long) {
        deleteDraft(accounts.get(accountId), draftId, skipTrashFolder = false)
    }

    override suspend fun deleteSkippingTrash(accountId: AccountId, draftId: Long) {
        deleteDraft(accounts.get(accountId), draftId, skipTrashFolder = true)
    }

    /** See [save]. */
    fun saveDraft(
        account: LegacyAccountDto,
        message: Message,
        existingDraftId: Long?,
        plaintextSubject: String?,
    ): Long? {
        return try {
            val draftsFolderId = account.draftsFolderId ?: error("No Drafts folder configured")

            if (accounts.backend(account).supportsUpload) {
                saveAndUploadDraft(account, message, draftsFolderId, existingDraftId, plaintextSubject)
            } else {
                saveDraftLocally(account, message, draftsFolderId, existingDraftId, plaintextSubject)
            }
        } catch (e: MessagingException) {
            logger.error(TAG, e) { "Unable to save message as draft." }
            null
        }
    }

    private fun saveAndUploadDraft(
        account: LegacyAccountDto,
        message: Message,
        folderId: Long,
        existingDraftId: Long?,
        subject: String?,
    ): Long {
        val messageStore = accounts.messageStore(account)

        val messageId = messageStore.saveLocalMessage(folderId, message.toSaveMessageData(subject))

        val previousDraftMessage = existingDraftId?.let {
            val localFolder = accounts.localStoreOrThrow(account).getFolder(folderId)
            localFolder.open()

            localFolder.getMessage(existingDraftId)
        }

        if (previousDraftMessage != null) {
            previousDraftMessage.delete()

            val deleteMessageId = previousDraftMessage.databaseId
            pendingCommands.add(account, PendingReplace.create(folderId, messageId, deleteMessageId))
        } else {
            val fakeMessageServerId = messageStore.getMessageServerId(messageId)
            if (fakeMessageServerId != null) {
                pendingCommands.add(account, PendingAppend.create(folderId, fakeMessageServerId))
            }
        }

        pendingCommands.processInBackground(account)

        return messageId
    }

    private fun saveDraftLocally(
        account: LegacyAccountDto,
        message: Message,
        folderId: Long,
        existingDraftId: Long?,
        plaintextSubject: String?,
    ): Long {
        val messageStore = accounts.messageStore(account)
        val messageData = message.toSaveMessageData(plaintextSubject)

        return messageStore.saveLocalMessage(folderId, messageData, existingDraftId)
    }

    private fun deleteDraft(account: LegacyAccountDto, messageId: Long, skipTrashFolder: Boolean) {
        val folderId = account.draftsFolderId
        if (folderId == null) {
            logger.warn(TAG) { "No Drafts folder configured. Can't delete draft." }
            return
        }

        val messageServerId = accounts.messageStore(account).getMessageServerId(messageId)
        if (messageServerId != null) {
            val messageReference = MessageReference(account.uuid, folderId, messageServerId)
            deleteRepository.deleteMessages(listOf(messageReference), skipTrashFolder)
        }
    }

    private fun Message.toSaveMessageData(subject: String?): SaveMessageData {
        return saveMessageDataCreator.createSaveMessageData(this, MessageDownloadState.FULL, subject)
    }
}
