package net.thunderbird.feature.mail.sync.api

import com.fsck.k9.mail.Message
import net.thunderbird.feature.account.AccountId

/**
 * Saves and discards drafts in the account's drafts folder.
 *
 * Drafts are saved locally right away and uploaded in the background when the server supports it, see
 * [MessageFlagRepository].
 */
interface MessageDraftRepository {
    /**
     * Saves [message] as a draft, replacing the draft [existingDraftId] if given.
     *
     * @return the database ID of the saved draft, or `null` if it couldn't be saved (e.g. there's no drafts folder).
     */
    suspend fun save(accountId: AccountId, message: Message, existingDraftId: Long?, plaintextSubject: String?): Long?

    /** Deletes the draft with database ID [draftId], moving it to the trash folder like any deleted message. */
    suspend fun delete(accountId: AccountId, draftId: Long)

    /** Deletes the draft with database ID [draftId] without keeping a copy in the trash folder (it was sent). */
    suspend fun deleteSkippingTrash(accountId: AccountId, draftId: Long)
}
