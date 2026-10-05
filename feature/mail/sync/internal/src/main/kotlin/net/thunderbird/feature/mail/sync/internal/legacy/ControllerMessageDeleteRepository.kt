package net.thunderbird.feature.mail.sync.internal.legacy

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.controller.MessagingController
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.MessageDeleteRepository

internal class ControllerMessageDeleteRepository(
    private val controller: MessagingController,
    private val accounts: LegacyAccounts,
) : MessageDeleteRepository {
    override suspend fun delete(messages: List<MessageReference>) {
        controller.deleteMessages(messages)
    }

    override suspend fun deleteThreads(messages: List<MessageReference>) {
        controller.deleteThreads(messages)
    }

    override suspend fun expunge(accountId: AccountId, folderId: Long) {
        controller.expunge(accounts.get(accountId), folderId)
    }

    override suspend fun emptyTrash(accountId: AccountId) {
        controller.emptyTrash(accounts.get(accountId), null)
    }

    override suspend fun emptySpam(accountId: AccountId) {
        controller.emptySpam(accounts.get(accountId), null)
    }

    override suspend fun clearLocalMessages(accountId: AccountId, folderId: Long) {
        controller.clearFolder(accounts.get(accountId), folderId)
    }
}
