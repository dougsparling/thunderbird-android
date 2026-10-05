package net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase

import kotlinx.coroutines.flow.Flow
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.MailSynchronizer
import net.thunderbird.feature.mail.sync.api.SyncEvent

/** Records mail checks; the drawer uses nothing else. */
internal class FakeMailSynchronizer : MailSynchronizer {
    val recordedCheckMail = mutableListOf<CheckMailParameters>()

    override suspend fun checkMail(
        accountId: AccountId?,
        ignoreLastCheckedTime: Boolean,
        useManualWakeLock: Boolean,
        notify: Boolean,
    ) {
        recordedCheckMail.add(CheckMailParameters(accountId, ignoreLastCheckedTime, useManualWakeLock, notify))
    }

    override fun requestFolderSync(accountId: AccountId, folderId: Long, notify: Boolean) = notUsed()

    override fun requestMoreMessages(accountId: AccountId, folderId: Long) = notUsed()

    override fun requestFolderListRefresh(accountId: AccountId) = notUsed()

    override suspend fun refreshFolderList(accountId: AccountId) = notUsed()

    override fun requestCheckMail(
        accountId: AccountId?,
        ignoreLastCheckedTime: Boolean,
        useManualWakeLock: Boolean,
        notify: Boolean,
    ) = notUsed()

    override suspend fun syncPeriodically(accountId: AccountId): Boolean = notUsed()

    override suspend fun syncPushedFolder(accountId: AccountId, folderServerId: String) = notUsed()

    override fun reportError(accountId: AccountId, exception: Exception) = notUsed()

    override fun checkAuthenticationProblem(accountId: AccountId) = notUsed()

    override fun observeEvents(): Flow<SyncEvent> = notUsed()

    private fun notUsed(): Nothing = error("Not used by the drawer")
}

internal data class CheckMailParameters(
    val accountId: AccountId?,
    val ignoreLastCheckedTime: Boolean,
    val useManualWakeLock: Boolean,
    val notify: Boolean,
)
