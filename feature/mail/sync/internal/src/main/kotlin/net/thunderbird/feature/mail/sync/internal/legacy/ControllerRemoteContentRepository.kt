package net.thunderbird.feature.mail.sync.internal.legacy

import app.k9mail.legacy.message.controller.MessageReference
import app.k9mail.legacy.message.controller.SimpleMessagingListener
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Part
import com.fsck.k9.mailstore.LocalPart
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.DownloadError
import net.thunderbird.feature.mail.sync.api.RemoteContentRepository
import net.thunderbird.feature.mail.sync.api.RemoteSearchEvent

internal class ControllerRemoteContentRepository(
    private val controller: MessagingController,
    private val accounts: LegacyAccounts,
) : RemoteContentRepository {
    override suspend fun downloadPartial(message: MessageReference) = download(message, complete = false)

    override suspend fun downloadComplete(message: MessageReference) = download(message, complete = true)

    private suspend fun download(message: MessageReference, complete: Boolean): Outcome<Unit, DownloadError> {
        val account = accounts.get(message)
        return suspendCancellableCoroutine { continuation ->
            val listener = object : SimpleMessagingListener() {
                override fun loadMessageRemoteFinished(account: LegacyAccountDto?, folderId: Long, uid: String?) {
                    if (continuation.isActive) continuation.resume(Outcome.Success(Unit))
                }

                override fun loadMessageRemoteFailed(
                    account: LegacyAccountDto?,
                    folderId: Long,
                    uid: String?,
                    t: Throwable?,
                ) {
                    val error = if (t is IllegalArgumentException) {
                        DownloadError.MessageNotFound
                    } else {
                        DownloadError.Failed(t?.message)
                    }
                    if (continuation.isActive) continuation.resume(Outcome.Failure(error))
                }
            }

            if (complete) {
                controller.loadMessageRemote(account, message.folderId, message.uid, listener)
            } else {
                controller.loadMessageRemotePartial(account, message.folderId, message.uid, listener)
            }
        }
    }

    override suspend fun downloadAttachment(part: Part): Outcome<Unit, DownloadError> {
        val localPart = part as LocalPart
        val account = accounts.get(localPart.accountUuid)
        return suspendCancellableCoroutine { continuation ->
            val listener = object : SimpleMessagingListener() {
                override fun loadAttachmentFinished(account: LegacyAccountDto?, message: Message?, part: Part?) {
                    if (continuation.isActive) continuation.resume(Outcome.Success(Unit))
                }

                override fun loadAttachmentFailed(
                    account: LegacyAccountDto?,
                    message: Message?,
                    part: Part?,
                    reason: String?,
                ) {
                    if (continuation.isActive) continuation.resume(Outcome.Failure(DownloadError.Failed(reason)))
                }
            }
            controller.loadAttachment(account, localPart.message, part, listener)
        }
    }

    override fun observeAttachmentProgress(): Flow<Int> = callbackFlow {
        val listener = object : SimpleMessagingListener() {
            override fun updateProgress(progress: Int) {
                trySend(progress)
            }
        }
        controller.addListener(listener)
        awaitClose { controller.removeListener(listener) }
    }.buffer(Channel.CONFLATED)

    override fun searchOnServer(
        accountId: AccountId,
        folderId: Long,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
    ): Flow<RemoteSearchEvent> = callbackFlow {
        val finished = AtomicBoolean(false)
        val listener = object : SimpleMessagingListener() {
            override fun remoteSearchStarted(folderId: Long) {
                trySend(RemoteSearchEvent.Started)
            }

            override fun remoteSearchServerQueryComplete(folderId: Long, numResults: Int, maxResults: Int) {
                trySend(RemoteSearchEvent.ServerQueryComplete(numResults, maxResults))
            }

            override fun remoteSearchFailed(folderServerId: String?, err: String?) {
                trySend(RemoteSearchEvent.Failed(err))
            }

            override fun remoteSearchFinished(
                folderId: Long,
                numResults: Int,
                maxResults: Int,
                extraResults: List<String>?,
            ) {
                finished.set(true)
                trySend(RemoteSearchEvent.Finished(maxResults, extraResults.orEmpty()))
                close()
            }
        }
        val search = controller.searchRemoteMessages(
            accountId.toString(),
            folderId,
            query,
            requiredFlags,
            forbiddenFlags,
            listener,
        )
        awaitClose {
            // Stops a search that's still running; one that finished is left to return on its own.
            if (!finished.get()) search.cancel(true)
        }
    }.buffer(Channel.UNLIMITED)

    override suspend fun loadSearchResults(accountId: AccountId, folderId: Long, messageServerIds: List<String>) {
        val account = accounts.get(accountId)
        suspendCancellableCoroutine { continuation ->
            val listener = object : SimpleMessagingListener() {
                override fun enableProgressIndicator(enable: Boolean) {
                    if (!enable && continuation.isActive) continuation.resume(Unit)
                }
            }
            controller.loadSearchResults(account, folderId, messageServerIds, listener)
        }
    }
}
