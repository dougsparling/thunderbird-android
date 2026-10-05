package net.thunderbird.feature.mail.sync.api

import app.k9mail.legacy.message.controller.MessageReference
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId

/**
 * Changes the flags of messages (read, starred, answered, ...).
 *
 * Like all local changes, a change is applied to the app's copy of the messages and then sent to the server in the
 * background; changes reach the server in the order they were made, and are retried until the server takes them or
 * rejects them for good. The change is taken as soon as the call starts: cancelling the caller only stops waiting,
 * it never drops or undoes the change.
 */
interface MessageFlagRepository {
    /** Sets or clears [flag] on the messages with the given database IDs. */
    suspend fun update(accountId: AccountId, messageIds: List<Long>, flag: Flag, newState: Boolean)

    /** Sets or clears [flag] on all messages of the threads with the given root message IDs. */
    suspend fun updateThreads(accountId: AccountId, threadRootIds: List<Long>, flag: Flag, newState: Boolean)

    /** Sets or clears [flag] on [message], if the app has it. */
    suspend fun update(message: MessageReference, flag: Flag, newState: Boolean)

    /** Marks every message in the folder as read. */
    suspend fun markAllAsRead(accountId: AccountId, folderId: Long)

    /**
     * Records that the user opened [message]: dismisses its new-mail notification and marks it as read if the account
     * is set to (otherwise only as "not new").
     *
     * @return whether the message was marked as read; the caller updates its own copy of the message.
     */
    suspend fun markAsOpened(message: MessageReference): Boolean

    /** Marks all of the account's messages as "not new". */
    suspend fun clearNewMessages(accountId: AccountId)
}
