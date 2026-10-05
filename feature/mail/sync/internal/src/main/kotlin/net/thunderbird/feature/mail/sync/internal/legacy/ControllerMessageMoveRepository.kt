package net.thunderbird.feature.mail.sync.internal.legacy

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.controller.MessagingController
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.MessageMoveRepository

internal class ControllerMessageMoveRepository(
    private val controller: MessagingController,
    private val accounts: LegacyAccounts,
) : MessageMoveRepository {
    override suspend fun move(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    ) {
        controller.moveMessages(accounts.get(accountId), sourceFolderId, messages, destinationFolderId)
    }

    override suspend fun moveThreads(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    ) {
        controller.moveMessagesInThread(accounts.get(accountId), sourceFolderId, messages, destinationFolderId)
    }

    override suspend fun copy(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    ) {
        controller.copyMessages(accounts.get(accountId), sourceFolderId, messages, destinationFolderId)
    }

    override suspend fun copyThreads(
        accountId: AccountId,
        sourceFolderId: Long,
        messages: List<MessageReference>,
        destinationFolderId: Long,
    ) {
        controller.copyMessagesInThread(accounts.get(accountId), sourceFolderId, messages, destinationFolderId)
    }

    override suspend fun archive(messages: List<MessageReference>) {
        controller.archiveMessages(messages)
    }

    override suspend fun archiveThreads(messages: List<MessageReference>) {
        controller.archiveThreads(messages)
    }

    override suspend fun moveToDrafts(accountId: AccountId, folderId: Long, messages: List<MessageReference>) {
        controller.moveToDraftsFolder(accounts.get(accountId), folderId, messages)
    }
}
