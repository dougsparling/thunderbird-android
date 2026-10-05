package net.thunderbird.feature.mail.sync.internal

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.mail.Part
import com.fsck.k9.mailstore.LocalFolder
import com.fsck.k9.mailstore.LocalPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.message.list.LocalMessageUidPrefixProvider
import net.thunderbird.feature.mail.sync.api.DownloadError
import net.thunderbird.feature.mail.sync.api.RemoteContentRepository
import net.thunderbird.feature.mail.sync.api.RemoteSearchEvent
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import net.thunderbird.feature.mail.sync.internal.engine.WorkPriority

private const val TAG = "RemoteContentRepository"

@Suppress("LongParameterList")
internal class DefaultRemoteContentRepository(
    private val accounts: AccountStores,
    private val serializer: RemoteWorkSerializer,
    private val serverErrorNotifier: ServerErrorNotifier,
    private val localMessageUidPrefixProvider: LocalMessageUidPrefixProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: Logger,
    private val syncDebugLogger: Logger,
) : RemoteContentRepository {
    private val attachmentProgress = MutableSharedFlow<Int>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override suspend fun downloadPartial(message: MessageReference) = download(message, complete = false)

    override suspend fun downloadComplete(message: MessageReference) = download(message, complete = true)

    private suspend fun download(message: MessageReference, complete: Boolean): Outcome<Unit, DownloadError> {
        val account = accounts.get(message)
        val result = CompletableDeferred<Outcome<Unit, DownloadError>>()
        val description = if (complete) "loadMessageRemote" else "loadMessageRemotePartial"
        serializer.enqueue(description, WorkPriority.FOREGROUND) {
            result.complete(downloadBlocking(account, message.folderId, message.uid, complete))
        }
        return result.await()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun downloadBlocking(
        account: LegacyAccountDto,
        folderId: Long,
        messageServerId: String,
        complete: Boolean,
    ): Outcome<Unit, DownloadError> {
        return try {
            require(!messageServerId.startsWith(localMessageUidPrefixProvider.get())) {
                "Must not be called with a local UID"
            }

            val folderServerId = accounts.folderServerId(account, folderId)
            val backend = accounts.backend(account)

            if (complete) {
                withContext(ioDispatcher) {
                    backend.downloadCompleteMessage(folderServerId, messageServerId)
                }
            } else {
                backend.downloadMessage(createSyncConfig(account), folderServerId, messageServerId)
            }

            Outcome.Success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            val error = if (e is IllegalArgumentException) {
                DownloadError.MessageNotFound
            } else {
                DownloadError.Failed(e.message)
            }

            serverErrorNotifier.notifyUserIfCertificateProblem(account, e, incoming = true)
            logger.error(TAG, e) { "Error while loading remote message" }
            syncDebugLogger.error("MessagingException") { "Error while loading remote message" }

            Outcome.Failure(error)
        }
    }

    override suspend fun downloadAttachment(part: Part): Outcome<Unit, DownloadError> {
        val localPart = part as LocalPart
        val account = accounts.get(localPart.accountUuid)
        val message = localPart.message
        val result = CompletableDeferred<Outcome<Unit, DownloadError>>()
        serializer.enqueue("loadAttachment", WorkPriority.FOREGROUND) {
            try {
                val folderServerId = message.folder.serverId

                val localFolder = accounts.localStore(account).getFolder(folderServerId)

                val bodyFactory = ProgressBodyFactory { progress -> attachmentProgress.tryEmit(progress) }

                val backend = accounts.backend(account)
                backend.fetchPart(folderServerId, message.uid, part, bodyFactory)

                localFolder.addPartToMessage(message, part)

                result.complete(Outcome.Success(Unit))
            } catch (e: MessagingException) {
                logger.verbose(TAG, e) { "Exception loading attachment" }

                result.complete(Outcome.Failure(DownloadError.Failed(e.message)))
                serverErrorNotifier.notifyUserIfCertificateProblem(account, e, incoming = true)
            }
        }
        return result.await()
    }

    override fun observeAttachmentProgress(): Flow<Int> = attachmentProgress.asSharedFlow()

    @Suppress("TooGenericExceptionCaught")
    override fun searchOnServer(
        accountId: AccountId,
        folderId: Long,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
    ): Flow<RemoteSearchEvent> = channelFlow {
        logger.info(TAG) { "searchOnServer (accountId = $accountId, folderId = $folderId)" }

        val account = accounts.get(accountId)

        send(RemoteSearchEvent.Started)

        var extraResults = emptyList<String>()
        try {
            val localFolder = openFolder(account, folderId)
            val folderServerId = localFolder.serverId

            val backend = accounts.backend(account)

            val performFullTextSearch = account.isRemoteSearchFullText
            var messageServerIds = runInterruptible {
                backend.search(folderServerId, query, requiredFlags, forbiddenFlags, performFullTextSearch)
            }

            logger.info(TAG) { "Remote search got ${messageServerIds.size} results" }

            // There's no need to fetch messages already completely downloaded
            messageServerIds = localFolder.extractNewMessages(messageServerIds)

            send(RemoteSearchEvent.ServerQueryComplete(messageServerIds.size, account.remoteSearchNumResults))

            val resultLimit = account.remoteSearchNumResults
            if (resultLimit > 0 && messageServerIds.size > resultLimit) {
                extraResults = messageServerIds.subList(resultLimit, messageServerIds.size)
                messageServerIds = messageServerIds.subList(0, resultLimit)
            }

            runInterruptible { downloadSearchResults(account, messageServerIds, localFolder) }
        } catch (e: CancellationException) {
            logger.info(TAG, e) { "Remote search was cancelled" }
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Could not complete remote search" }
            send(RemoteSearchEvent.Failed(e.message))
            logger.error(TAG, e) { "Remote search failed for account $account, folder $folderId" }
        }

        send(RemoteSearchEvent.Finished(account.remoteSearchNumResults, extraResults))
    }.flowOn(ioDispatcher)

    override suspend fun loadSearchResults(accountId: AccountId, folderId: Long, messageServerIds: List<String>) {
        val account = accounts.get(accountId)
        withContext(ioDispatcher) {
            try {
                val localFolder = openFolder(account, folderId)
                downloadSearchResults(account, messageServerIds, localFolder)
            } catch (e: MessagingException) {
                logger.error(TAG, e) { "Exception in loadSearchResults" }
            }
        }
    }

    private fun openFolder(account: LegacyAccountDto, folderId: Long): LocalFolder {
        val localFolder = accounts.localStore(account).getFolder(folderId)
        if (!localFolder.exists()) {
            throw MessagingException("Folder not found")
        }

        localFolder.open()
        return localFolder
    }

    private fun downloadSearchResults(
        account: LegacyAccountDto,
        messageServerIds: List<String>,
        localFolder: LocalFolder,
    ) {
        val backend = accounts.backend(account)
        val folderServerId = localFolder.serverId

        for (messageServerId in messageServerIds) {
            val localMessage = localFolder.getMessage(messageServerId)

            if (localMessage == null) {
                backend.downloadMessageStructure(folderServerId, messageServerId)
            }
        }
    }
}
