package net.thunderbird.feature.mail.sync.api

import app.k9mail.legacy.message.controller.MessageReference
import net.thunderbird.feature.account.AccountId

/**
 * What an account's mail server supports, and whether a message can be acted on remotely yet.
 *
 * Answers come from the account's server type and settings, without talking to the server. An account that doesn't
 * exist supports nothing.
 */
interface MessageCapabilities {
    fun isMoveCapable(accountId: AccountId): Boolean

    fun isCopyCapable(accountId: AccountId): Boolean

    fun isPushCapable(accountId: AccountId): Boolean

    fun supportsFlags(accountId: AccountId): Boolean

    fun supportsExpunge(accountId: AccountId): Boolean

    fun supportsSearchByDate(accountId: AccountId): Boolean

    fun supportsUpload(accountId: AccountId): Boolean

    fun supportsFolderSubscriptions(accountId: AccountId): Boolean

    /** Whether [message] exists on the server, so it can be moved. Messages that were never synced can't. */
    fun isMoveCapable(message: MessageReference): Boolean

    /** Whether [message] exists on the server, so it can be copied. Messages that were never synced can't. */
    fun isCopyCapable(message: MessageReference): Boolean
}
