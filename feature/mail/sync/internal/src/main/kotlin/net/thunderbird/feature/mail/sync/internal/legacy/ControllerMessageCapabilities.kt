package net.thunderbird.feature.mail.sync.internal.legacy

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.controller.MessagingController
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.MessageCapabilities

internal class ControllerMessageCapabilities(
    private val controller: MessagingController,
    private val accounts: LegacyAccounts,
) : MessageCapabilities {
    override fun isMoveCapable(accountId: AccountId) = check(accountId, controller::isMoveCapable)

    override fun isCopyCapable(accountId: AccountId) = check(accountId, controller::isCopyCapable)

    override fun isPushCapable(accountId: AccountId) = check(accountId, controller::isPushCapable)

    override fun supportsFlags(accountId: AccountId) = check(accountId, controller::supportsFlags)

    override fun supportsExpunge(accountId: AccountId) = check(accountId, controller::supportsExpunge)

    override fun supportsSearchByDate(accountId: AccountId) = check(accountId, controller::supportsSearchByDate)

    override fun supportsUpload(accountId: AccountId) = check(accountId, controller::supportsUpload)

    override fun supportsFolderSubscriptions(accountId: AccountId) =
        check(accountId, controller::supportsFolderSubscriptions)

    override fun isMoveCapable(message: MessageReference) = controller.isMoveCapable(message)

    override fun isCopyCapable(message: MessageReference) = controller.isCopyCapable(message)

    private inline fun check(accountId: AccountId, capability: (LegacyAccountDto) -> Boolean): Boolean {
        val account = accounts.find(accountId) ?: return false
        return capability(account)
    }
}
