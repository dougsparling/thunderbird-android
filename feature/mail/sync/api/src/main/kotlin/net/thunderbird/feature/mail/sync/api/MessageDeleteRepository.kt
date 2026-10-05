package net.thunderbird.feature.mail.sync.api

import app.k9mail.legacy.message.controller.MessageReference
import net.thunderbird.feature.account.AccountId

/**
 * Deletes messages and empties folders.
 *
 * Changes are applied locally and sent to the server in the background, see [MessageFlagRepository]. What reaches the
 * server depends on the account's delete policy, its trash folder and its expunge setting.
 */
interface MessageDeleteRepository {
    /**
     * Deletes [messages] of any accounts: moves them to the trash folder, or removes them where that's not possible.
     */
    suspend fun delete(messages: List<MessageReference>)

    /** Like [delete], for the whole threads the [messages] belong to. */
    suspend fun deleteThreads(messages: List<MessageReference>)

    /** Removes the messages marked as deleted from the folder on the server. */
    suspend fun expunge(accountId: AccountId, folderId: Long)

    /** Deletes every message in the account's trash folder, if it has one. */
    suspend fun emptyTrash(accountId: AccountId)

    /** Deletes every message in the account's spam folder, if it has one. */
    suspend fun emptySpam(accountId: AccountId)

    /** Removes the app's copies of the folder's messages; the server isn't changed. */
    suspend fun clearLocalMessages(accountId: AccountId, folderId: Long)
}
