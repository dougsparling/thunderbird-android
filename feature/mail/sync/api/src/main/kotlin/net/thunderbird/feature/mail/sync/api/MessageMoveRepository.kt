package net.thunderbird.feature.mail.sync.api

import app.k9mail.legacy.message.controller.MessageReference
import net.thunderbird.feature.account.AccountId

/**
 * Moves and copies messages between folders, including archiving.
 *
 * Changes are applied locally and sent to the server in the background, see [MessageFlagRepository]. Moves and copies
 * need the account to be [move][MessageCapabilities.isMoveCapable] or [copy][MessageCapabilities.isCopyCapable]
 * capable; messages that aren't on the server yet are skipped.
 */
interface MessageMoveRepository {
    suspend fun move(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    )

    /** Moves the whole threads the [messages] belong to. */
    suspend fun moveThreads(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    )

    suspend fun copy(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    )

    /** Copies the whole threads the [messages] belong to. */
    suspend fun copyThreads(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    )

    /**
     * Moves [messages] (of any accounts) to their account's archive folder. Accounts without an archive folder, and
     * messages already in it, are left alone.
     */
    suspend fun archive(messages: List<MessageReference>)

    /** Like [archive], for the whole threads the [messages] belong to. */
    suspend fun archiveThreads(messages: List<MessageReference>)

    /**
     * Saves each of [messages] as a new draft and removes the app's copy of the original.
     *
     * The original stays on the server, so it comes back with the next sync.
     */
    suspend fun moveToDrafts(accountId: AccountId, folderId: Long, messages: List<MessageReference>)
}
