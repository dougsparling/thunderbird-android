package net.thunderbird.feature.mail.sync.api

import com.fsck.k9.mail.Message
import net.thunderbird.feature.account.AccountId

/** Sends messages through the account's outgoing server. */
interface OutboxSender {
    /** Puts [message] in the outbox and queues sending everything there. */
    suspend fun send(accountId: AccountId, message: Message, plaintextSubject: String?)

    /** Queues sending the messages waiting in the account's outbox. */
    fun requestSendPending(accountId: AccountId)

    /**
     * Sends [message] right away, bypassing the outbox; it's neither stored nor uploaded to the sent folder.
     *
     * @throws net.thunderbird.core.common.exception.MessagingException if sending fails.
     */
    suspend fun sendNow(accountId: AccountId, message: Message)
}
