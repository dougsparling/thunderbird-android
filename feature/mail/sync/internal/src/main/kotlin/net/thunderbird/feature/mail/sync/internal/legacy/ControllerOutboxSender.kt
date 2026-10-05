package net.thunderbird.feature.mail.sync.internal.legacy

import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mail.Message
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.OutboxSender

internal class ControllerOutboxSender(
    private val controller: MessagingController,
    private val accounts: LegacyAccounts,
    private val ioDispatcher: CoroutineDispatcher,
) : OutboxSender {
    override suspend fun send(accountId: AccountId, message: Message, plaintextSubject: String?) {
        controller.sendMessage(accounts.get(accountId), message, plaintextSubject, null)
    }

    override fun requestSendPending(accountId: AccountId) {
        controller.sendPendingMessages(accounts.get(accountId), null)
    }

    override suspend fun sendNow(accountId: AccountId, message: Message) {
        val account = accounts.get(accountId)
        withContext(ioDispatcher) {
            controller.sendMessageBlocking(account, message)
        }
    }
}
