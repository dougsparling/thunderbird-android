package net.thunderbird.feature.mail.sync.internal.legacy

import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mail.Message
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.MessageDraftRepository

internal class ControllerMessageDraftRepository(
    private val controller: MessagingController,
    private val accounts: LegacyAccounts,
) : MessageDraftRepository {
    override suspend fun save(
        accountId: AccountId,
        message: Message,
        existingDraftId: Long?,
        plaintextSubject: String?,
    ): Long? {
        return controller.saveDraft(accounts.get(accountId), message, existingDraftId, plaintextSubject)
    }

    override suspend fun delete(accountId: AccountId, draftId: Long) {
        controller.deleteDraft(accounts.get(accountId), draftId)
    }

    override suspend fun deleteSkippingTrash(accountId: AccountId, draftId: Long) {
        controller.deleteDraftSkippingTrashFolder(accounts.get(accountId), draftId)
    }
}
