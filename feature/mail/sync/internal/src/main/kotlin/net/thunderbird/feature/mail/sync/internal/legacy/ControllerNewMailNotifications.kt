package net.thunderbird.feature.mail.sync.internal.legacy

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.controller.MessagingController
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.NewMailNotifications
import net.thunderbird.feature.search.legacy.LocalMessageSearch

internal class ControllerNewMailNotifications(
    private val controller: MessagingController,
    private val accounts: LegacyAccounts,
) : NewMailNotifications {
    override fun clearForMessageList(search: LocalMessageSearch) {
        controller.clearNotifications(search)
    }

    override fun clearForAccount(accountId: AccountId) {
        controller.cancelNotificationsForAccount(accounts.get(accountId))
    }

    override fun clearForMessage(message: MessageReference) {
        controller.cancelNotificationForMessage(accounts.get(message), message)
    }

    override fun onAccountRemoved(accountId: AccountId) {
        controller.deleteAccount(accounts.get(accountId))
    }
}
