package net.thunderbird.feature.mail.sync.internal

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.mailstore.LocalMessageReader
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.featureflag.FeatureFlagProvider
import net.thunderbird.core.featureflag.keys.GeneratedFeatureFlagKey
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.MessageMoveRepository
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import net.thunderbird.feature.mail.sync.internal.engine.WorkPriority

private const val TAG = "MessageMoveRepository"

@Suppress("LongParameterList")
internal class DefaultMessageMoveRepository(
    private val accounts: AccountStores,
    private val localMessages: LocalMessages,
    private val messageMover: MessageMover,
    private val draftRepository: DefaultMessageDraftRepository,
    private val serializer: RemoteWorkSerializer,
    private val localMessageReader: LocalMessageReader,
    private val featureFlagProvider: FeatureFlagProvider,
    private val logger: Logger,
) : MessageMoveRepository {
    override suspend fun move(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    ) {
        localMessages.forFolder(accounts.get(accountId), sourceFolderId, messages) { account, _, messagesInFolder ->
            localMessages.hide(account, messagesInFolder)

            serializer.enqueue("moveMessages", WorkPriority.BACKGROUND) {
                messageMover.moveOrCopy(
                    account,
                    sourceFolderId,
                    messagesInFolder,
                    destinationFolderId,
                    MoveOrCopyFlavor.MOVE,
                )
            }
        }
    }

    override suspend fun moveThreads(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    ) {
        localMessages.forFolder(accounts.get(accountId), sourceFolderId, messages) { account, _, messagesInFolder ->
            localMessages.hide(account, messagesInFolder)

            serializer.enqueue("moveMessagesInThread", WorkPriority.BACKGROUND) {
                try {
                    val messagesInThreads = localMessages.collectMessagesInThreads(account, messagesInFolder)
                    messageMover.moveOrCopy(
                        account,
                        sourceFolderId,
                        messagesInThreads,
                        destinationFolderId,
                        MoveOrCopyFlavor.MOVE,
                    )
                } catch (e: MessagingException) {
                    logger.error(TAG, e) { "Exception while moving messages" }
                }
            }
        }
    }

    override suspend fun copy(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    ) {
        localMessages.forFolder(accounts.get(accountId), sourceFolderId, messages) { account, _, messagesInFolder ->
            serializer.enqueue("copyMessages", WorkPriority.BACKGROUND) {
                messageMover.moveOrCopy(
                    account,
                    sourceFolderId,
                    messagesInFolder,
                    destinationFolderId,
                    MoveOrCopyFlavor.COPY,
                )
            }
        }
    }

    override suspend fun copyThreads(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    ) {
        localMessages.forFolder(accounts.get(accountId), sourceFolderId, messages) { account, _, messagesInFolder ->
            serializer.enqueue("copyMessagesInThread", WorkPriority.BACKGROUND) {
                try {
                    val messagesInThreads = localMessages.collectMessagesInThreads(account, messagesInFolder)
                    messageMover.moveOrCopy(
                        account,
                        sourceFolderId,
                        messagesInThreads,
                        destinationFolderId,
                        MoveOrCopyFlavor.COPY,
                    )
                } catch (e: MessagingException) {
                    logger.error(TAG, e) { "Exception while copying messages" }
                }
            }
        }
    }

    override suspend fun archive(messages: List<MessageReference>) {
        archiveByFolder("archiveMessages", messages) { account, sourceFolderId, messagesInFolder, archiveFolderId ->
            archiveMessages(account, sourceFolderId, messagesInFolder, archiveFolderId)
        }
    }

    override suspend fun archiveThreads(messages: List<MessageReference>) {
        archiveByFolder("archiveThreads", messages) { account, sourceFolderId, messagesInFolder, archiveFolderId ->
            val messagesInThreads = localMessages.collectMessagesInThreads(account, messagesInFolder)
            archiveMessages(account, sourceFolderId, messagesInThreads, archiveFolderId)
        }
    }

    override suspend fun moveToDrafts(accountId: AccountId, folderId: Long, messages: List<MessageReference>) {
        val account = accounts.get(accountId)
        serializer.enqueue("moveToDrafts", WorkPriority.BACKGROUND) {
            for (messageReference in messages) {
                try {
                    val message = localMessageReader.loadMessage(account, folderId, messageReference.uid)
                    val draftMessageId = draftRepository.saveDraft(account, message, null, message.subject)

                    val draftSavedSuccessfully = draftMessageId != null
                    if (draftSavedSuccessfully) {
                        message.destroy()
                    }

                    accounts.notifyFolderChanged(account, folderId)
                } catch (e: MessagingException) {
                    logger.error(TAG, e) { "Error loading message. Draft was not saved." }
                }
            }
        }
    }

    private fun archiveByFolder(
        description: String,
        messages: List<MessageReference>,
        action: (
            account: LegacyAccountDto,
            sourceFolderId: Long,
            messagesInFolder: List<LocalMessage>,
            archiveFolderId: Long,
        ) -> Unit,
    ) {
        localMessages.forEachFolder(messages) { account, messageFolder, messagesInFolder ->
            val sourceFolderId = messageFolder.databaseId
            when (val archiveFolderId = account.archiveFolderId) {
                null -> {
                    logger.verbose(TAG) { "No archive folder configured for account $account" }
                }

                sourceFolderId -> {
                    logger.verbose(TAG) { "Skipping messages already in archive folder" }
                }

                else -> {
                    localMessages.hide(account, messagesInFolder)
                    serializer.enqueue(description, WorkPriority.BACKGROUND) {
                        action(account, sourceFolderId, messagesInFolder, archiveFolderId)
                    }
                }
            }
        }
    }

    private fun archiveMessages(
        account: LegacyAccountDto,
        sourceFolderId: Long,
        messages: List<LocalMessage>,
        archiveFolderId: Long,
    ) {
        val operation = featureFlagProvider.provide(GeneratedFeatureFlagKey.ARCHIVE_MARKS_AS_READ)
            .whenEnabledOrNot(
                onEnabled = { MoveOrCopyFlavor.MOVE_AND_MARK_AS_READ },
                onDisabledOrUnavailable = { MoveOrCopyFlavor.MOVE },
            )
        messageMover.moveOrCopy(account, sourceFolderId, messages, archiveFolderId, operation)
    }
}
