package net.thunderbird.feature.mail.sync.internal.legacy

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.controller.MessagingController
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.MessageFlagRepository

internal class ControllerMessageFlagRepository(
    private val controller: MessagingController,
    private val accounts: LegacyAccounts,
) : MessageFlagRepository {
    override suspend fun update(accountId: AccountId, messageIds: List<Long>, flag: Flag, newState: Boolean) {
        controller.setFlag(accounts.get(accountId), messageIds, flag, newState)
    }

    override suspend fun updateThreads(accountId: AccountId, threadRootIds: List<Long>, flag: Flag, newState: Boolean) {
        controller.setFlagForThreads(accounts.get(accountId), threadRootIds, flag, newState)
    }

    override suspend fun update(message: MessageReference, flag: Flag, newState: Boolean) {
        controller.setFlag(accounts.get(message), message.folderId, message.uid, flag, newState)
    }

    override suspend fun markAllAsRead(accountId: AccountId, folderId: Long) {
        controller.markAllMessagesRead(accounts.get(accountId), folderId)
    }

    override suspend fun markAsOpened(message: MessageReference): Boolean {
        val account = accounts.get(message)
        val localMessage = controller.loadMessageMetadata(account, message.folderId, message.uid)
        val markedAsRead = account.isMarkMessageAsReadOnView && !localMessage.isSet(Flag.SEEN)
        controller.markMessageAsOpened(account, localMessage)
        return markedAsRead
    }

    override suspend fun clearNewMessages(accountId: AccountId) {
        controller.clearNewMessages(accounts.get(accountId))
    }
}
