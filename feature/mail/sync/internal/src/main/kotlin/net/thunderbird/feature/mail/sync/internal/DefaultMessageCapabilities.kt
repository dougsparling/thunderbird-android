package net.thunderbird.feature.mail.sync.internal

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.backend.api.Backend
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.message.list.LocalMessageUidPrefixProvider
import net.thunderbird.feature.mail.sync.api.MessageCapabilities

internal class DefaultMessageCapabilities(
    private val accounts: AccountStores,
    private val localMessageUidPrefixProvider: LocalMessageUidPrefixProvider,
) : MessageCapabilities {
    override fun isMoveCapable(accountId: AccountId) = check(accountId) { it.supportsMove }

    override fun isCopyCapable(accountId: AccountId) = check(accountId) { it.supportsCopy }

    override fun isPushCapable(accountId: AccountId) = check(accountId) { it.isPushCapable }

    override fun supportsFlags(accountId: AccountId) = check(accountId) { it.supportsFlags }

    override fun supportsExpunge(accountId: AccountId) = check(accountId) { it.supportsExpunge }

    override fun supportsSearchByDate(accountId: AccountId) = check(accountId) { it.supportsSearchByDate }

    override fun supportsUpload(accountId: AccountId) = check(accountId) { it.supportsUpload }

    override fun supportsFolderSubscriptions(accountId: AccountId) = check(accountId) { it.supportsFolderSubscriptions }

    override fun isMoveCapable(message: MessageReference): Boolean {
        return !message.uid.startsWith(localMessageUidPrefixProvider.get())
    }

    override fun isCopyCapable(message: MessageReference) = isMoveCapable(message)

    private inline fun check(accountId: AccountId, capability: (Backend) -> Boolean): Boolean {
        val account = accounts.find(accountId) ?: return false
        return capability(accounts.backend(account))
    }
}
