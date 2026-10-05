package net.thunderbird.feature.mail.sync.api

import net.thunderbird.feature.account.AccountId

/** Progress of the app's background work with mail servers, see [MailSynchronizer.observeEvents]. */
sealed interface SyncEvent {
    /** A folder sync (or, for the outbox, a send) started. */
    data class FolderSyncStarted(val accountId: AccountId, val folderId: Long) : SyncEvent

    /** Headers of a folder's messages are being downloaded: [completed] of [total]. */
    data class FolderHeadersProgress(
        val accountId: AccountId,
        val folderServerId: String,
        val completed: Int,
        val total: Int,
    ) : SyncEvent

    /** All headers of a folder's messages were downloaded. */
    data class FolderHeadersFinished(
        val accountId: AccountId,
        val folderServerId: String,
        val totalMessagesInFolder: Int,
        val newMessageCount: Int,
    ) : SyncEvent

    /** Messages of a folder are being downloaded (or, for the outbox, sent): [completed] of [total]. */
    data class FolderSyncProgress(
        val accountId: AccountId,
        val folderId: Long,
        val completed: Int,
        val total: Int,
    ) : SyncEvent

    data class FolderSyncFinished(val accountId: AccountId, val folderId: Long) : SyncEvent

    /** A folder sync (or sending a message from the outbox) failed; [message] describes the root cause. */
    data class FolderSyncFailed(val accountId: AccountId, val folderId: Long, val message: String) : SyncEvent

    /** A mail check started, for one account or, if [accountId] is `null`, for all accounts. */
    data class CheckMailStarted(val accountId: AccountId?) : SyncEvent

    /** A mail check finished, for one account or, if [accountId] is `null`, for all accounts. */
    data class CheckMailFinished(val accountId: AccountId?) : SyncEvent

    /** A message created in the app (e.g. a draft or a moved message) got its ID from the server. */
    data class MessageUidChanged(
        val accountId: AccountId,
        val folderId: Long,
        val oldUid: String,
        val newUid: String,
    ) : SyncEvent
}
