package net.thunderbird.feature.mail.sync.internal

import com.fsck.k9.K9
import com.fsck.k9.controller.PendingAppend
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.CertificateValidationException
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mailstore.LocalFolder
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.mailstore.LocalStore
import com.fsck.k9.mailstore.SaveMessageDataCreator
import com.fsck.k9.mailstore.SendState
import com.fsck.k9.notification.NotificationController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.exception.rootCauseMessage
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.OutboxFolderManager
import net.thunderbird.feature.mail.sync.api.OutboxSender
import net.thunderbird.feature.mail.sync.api.SyncEvent
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import net.thunderbird.feature.mail.sync.internal.engine.WorkPriority

private const val TAG = "OutboxSender"

@Suppress("LongParameterList")
internal class DefaultOutboxSender(
    private val accounts: AccountStores,
    private val serializer: RemoteWorkSerializer,
    private val pendingCommands: PendingCommandQueue,
    private val events: SyncEventBus,
    private val serverErrorNotifier: ServerErrorNotifier,
    private val notificationController: NotificationController,
    private val outboxFolderManager: OutboxFolderManager,
    private val saveMessageDataCreator: SaveMessageDataCreator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: Logger,
) : OutboxSender {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun send(accountId: AccountId, message: Message, plaintextSubject: String?) {
        val account = accounts.get(accountId)
        try {
            val outboxFolderId = outboxFolderManager.getOutboxFolderIdSync(account.id, createIfMissing = true)

            message.setFlag(Flag.SEEN, true)

            val messageStore = accounts.messageStore(account)
            val messageData = saveMessageDataCreator.createSaveMessageData(
                message,
                MessageDownloadState.FULL,
                plaintextSubject,
            )
            val messageId = messageStore.saveLocalMessage(outboxFolderId, messageData, null)

            val outboxStateRepository = accounts.localStore(account).outboxStateRepository
            outboxStateRepository.initializeOutboxState(messageId)

            sendPendingInBackground(account)
        } catch (e: Exception) {
            logger.error(TAG, e) { "Error sending message" }
        }
    }

    override fun requestSendPending(accountId: AccountId) {
        sendPendingInBackground(accounts.get(accountId))
    }

    override suspend fun sendNow(accountId: AccountId, message: Message) {
        val account = accounts.get(accountId)
        withContext(ioDispatcher) {
            accounts.backend(account).sendMessage(message)
        }
    }

    /** Queues sending the messages in the account's outbox, as background work. */
    fun sendPendingInBackground(account: LegacyAccountDto) {
        serializer.enqueue("sendPendingMessages", WorkPriority.BACKGROUND) {
            if (outboxFolderManager.hasPendingMessages(account.id)) {
                showSendingNotificationIfNecessary(account)

                try {
                    sendPendingMessagesBlocking(account)
                } finally {
                    clearSendingNotificationIfNecessary(account)
                }
            }
        }
    }

    private fun showSendingNotificationIfNecessary(account: LegacyAccountDto) {
        if (account.isNotifySync) {
            notificationController.showSendingNotification(account)
        }
    }

    private fun clearSendingNotificationIfNecessary(account: LegacyAccountDto) {
        if (account.isNotifySync) {
            notificationController.clearSendingNotification(account)
        }
    }

    @Suppress(
        "TooGenericExceptionCaught",
        "LongMethod",
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "ReturnCount",
        "LoopWithTooManyJumpStatements",
    )
    private fun sendPendingMessagesBlocking(account: LegacyAccountDto) {
        var lastFailure: Exception? = null
        try {
            if (serverErrorNotifier.isAuthenticationProblem(account, incoming = false)) {
                logger.debug(TAG) { "Authentication will fail. Skip sending messages." }
                serverErrorNotifier.handleAuthenticationFailure(account, incoming = false)
                return
            }

            val localStore = accounts.localStore(account)
            val outboxStateRepository = localStore.outboxStateRepository
            val outboxFolderId = outboxFolderManager.getOutboxFolderIdSync(account.id, createIfMissing = true)
            val localFolder = localStore.getFolder(outboxFolderId)
            if (!localFolder.exists()) {
                logger.warn(TAG) { "Outbox does not exist" }
                return
            }

            localFolder.open()

            val localMessages = localFolder.messages
            var progress = 0
            val todo = localMessages.size
            events.emit(SyncEvent.FolderSyncProgress(account.id, outboxFolderId, progress, todo))

            // The profile we will use to pull all of the content for a given local message into memory for sending.
            val fetchProfile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.BODY)
            }

            logger.info(TAG) { "Scanning Outbox folder for messages to send" }

            val backend = accounts.backend(account)

            for (message in localMessages) {
                if (message.isSet(Flag.DELETED)) {
                    // TODO: When uploading a message to the remote Sent folder the move code creates a placeholder
                    //  message in the Outbox. This code gets rid of these messages. It'd be preferable if the
                    //  placeholder message was never created, though.
                    message.destroy()
                    continue
                }
                try {
                    val messageId = message.databaseId
                    val outboxState = outboxStateRepository.getOutboxState(messageId)

                    val sendState = outboxState.sendState
                    if (sendState != SendState.READY) {
                        logger.verbose(TAG) {
                            "Skipping sending message ${message.uid} " +
                                "(reason: ${sendState.databaseName} - ${outboxState.sendError})"
                        }

                        lastFailure = if (sendState == SendState.RETRIES_EXCEEDED) {
                            MessagingException("Retries exceeded", true)
                        } else {
                            MessagingException(outboxState.sendError, true)
                        }
                        continue
                    }

                    logger.info(TAG) {
                        "Send count for message ${message.uid} is ${outboxState.numberOfSendAttempts}"
                    }

                    localFolder.fetch(listOf(message), fetchProfile, null)
                    try {
                        if (message.getHeader(K9.IDENTITY_HEADER).isNotEmpty() || message.isSet(Flag.DRAFT)) {
                            logger.verbose(TAG) {
                                "The user has set the Outbox and Drafts folder to the same thing. " +
                                    "This message appears to be a draft, so K-9 will not send it"
                            }
                            continue
                        }

                        outboxStateRepository.incrementSendAttempts(messageId)
                        message.setFlag(Flag.X_SEND_IN_PROGRESS, true)

                        logger.info(TAG) { "Sending message with UID ${message.uid}" }
                        backend.sendMessage(message)

                        message.setFlag(Flag.X_SEND_IN_PROGRESS, false)
                        message.setFlag(Flag.SEEN, true)
                        progress++
                        events.emit(SyncEvent.FolderSyncProgress(account.id, outboxFolderId, progress, todo))
                        moveOrDeleteSentMessage(account, localStore, message)

                        outboxStateRepository.removeOutboxState(messageId)
                    } catch (e: AuthenticationFailedException) {
                        outboxStateRepository.decrementSendAttempts(messageId)
                        lastFailure = e

                        serverErrorNotifier.handleAuthenticationFailure(account, incoming = false)
                        handleSendFailure(account, localFolder, message, e)
                    } catch (e: CertificateValidationException) {
                        outboxStateRepository.decrementSendAttempts(messageId)
                        lastFailure = e

                        serverErrorNotifier.notifyUserIfCertificateProblem(account, e, incoming = false)
                        handleSendFailure(account, localFolder, message, e)
                    } catch (e: MessagingException) {
                        lastFailure = e

                        if (e.isPermanentFailure) {
                            outboxStateRepository.setSendAttemptError(messageId, e.message.orEmpty())
                        } else if (outboxState.numberOfSendAttempts + 1 >= K9.MAX_SEND_ATTEMPTS) {
                            outboxStateRepository.setSendAttemptsExceeded(messageId)
                        }

                        handleSendFailure(account, localFolder, message, e)
                    } catch (e: Exception) {
                        lastFailure = e

                        handleSendFailure(account, localFolder, message, e)
                    }
                } catch (e: Exception) {
                    lastFailure = e

                    logger.error(TAG, e) { "Failed to fetch message for sending" }
                    notifySendFailed(account, localFolder, e)
                }
            }

            lastFailure?.let { notificationController.showSendFailedNotification(account, it) }
        } catch (e: Exception) {
            logger.verbose(TAG, e) { "Failed to send pending messages" }
        } finally {
            if (lastFailure == null) {
                notificationController.clearSendFailedNotification(account)
            }
        }
    }

    private fun moveOrDeleteSentMessage(account: LegacyAccountDto, localStore: LocalStore, message: LocalMessage) {
        val sentFolderId = account.sentFolderId
        if (sentFolderId == null || !account.isUploadSentMessages) {
            logger.info(TAG) { "Not uploading sent message; deleting local message" }
            message.destroy()
        } else {
            val sentFolder = localStore.getFolder(sentFolderId)
            sentFolder.open()
            val sentFolderServerId = sentFolder.serverId
            logger.info(TAG) { "Moving sent message to folder '$sentFolderServerId' ($sentFolderId)" }

            val messageStore = accounts.messageStore(account)
            val destinationMessageId = messageStore.moveMessage(message.databaseId, sentFolderId)

            logger.info(TAG) { "Moved sent message to folder '$sentFolderServerId' ($sentFolderId)" }

            if (!sentFolder.isLocalOnly) {
                val destinationUid = messageStore.getMessageServerId(destinationMessageId)
                if (destinationUid != null) {
                    pendingCommands.add(account, PendingAppend.create(sentFolderId, destinationUid))
                    pendingCommands.processInBackground(account)
                }
            }
        }

        val outboxFolderId = outboxFolderManager.getOutboxFolderIdSync(account.id, createIfMissing = true)
        accounts.notifyFolderChanged(account, outboxFolderId)
    }

    private fun handleSendFailure(account: LegacyAccountDto, localFolder: LocalFolder, message: Message, e: Exception) {
        logger.error(TAG, e) { "Failed to send message" }
        message.setFlag(Flag.X_SEND_FAILED, true)

        notifySendFailed(account, localFolder, e)
    }

    private fun notifySendFailed(account: LegacyAccountDto, localFolder: LocalFolder, exception: Exception) {
        val folderId = localFolder.databaseId
        val errorMessage = exception.rootCauseMessage.orEmpty()
        events.emit(SyncEvent.FolderSyncFailed(account.id, folderId, errorMessage))
    }
}
