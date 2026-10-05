package net.thunderbird.feature.mail.sync.internal.legacy

import app.k9mail.legacy.message.controller.MessageReference
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.account.AccountId

/**
 * Finds the legacy account for an [AccountId].
 *
 * The account manager hands out the same instance every time, so changes the controller makes to it are seen by
 * everyone, as before.
 */
internal class LegacyAccounts(private val accountManager: LegacyAccountDtoManager) {
    fun find(accountId: AccountId): LegacyAccountDto? = accountManager.getAccount(accountId.toString())

    fun get(accountId: AccountId): LegacyAccountDto = find(accountId) ?: error("Account not found: $accountId")

    fun get(message: MessageReference): LegacyAccountDto = get(message.accountUuid)

    fun get(accountUuid: String): LegacyAccountDto {
        return accountManager.getAccount(accountUuid) ?: error("Account not found: $accountUuid")
    }
}
