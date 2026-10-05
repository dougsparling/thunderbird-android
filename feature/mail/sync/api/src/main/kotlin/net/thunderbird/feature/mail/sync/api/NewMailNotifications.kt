package net.thunderbird.feature.mail.sync.api

import app.k9mail.legacy.message.controller.MessageReference
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.search.legacy.LocalMessageSearch

/** Removes new-mail notifications when the user has seen the messages some other way. */
interface NewMailNotifications {
    /**
     * Removes the notifications for the messages a message list shows: one folder, the unified inbox or the "new
     * messages" view. Runs with the other work for the servers, ahead of background work.
     */
    fun clearForMessageList(search: LocalMessageSearch)

    fun clearForAccount(accountId: AccountId)

    fun clearForMessage(message: MessageReference)

    /** Removes everything kept for an account that is being removed. Call before the account is deleted. */
    fun onAccountRemoved(accountId: AccountId)
}
