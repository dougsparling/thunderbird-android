package net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.sync.api.MailSynchronizer
import net.thunderbird.feature.navigation.drawer.dropdown.domain.DomainContract.UseCase

internal class SyncAccount(
    private val mailSynchronizer: MailSynchronizer,
    private val coroutineContext: CoroutineContext = Dispatchers.IO,
) : UseCase.SyncAccount {
    override fun invoke(accountUuid: String): Flow<Result<Unit>> = flow {
        mailSynchronizer.checkMail(
            accountId = AccountIdFactory.of(accountUuid),
            ignoreLastCheckedTime = true,
            useManualWakeLock = true,
            notify = true,
        )

        emit(Result.success(Unit))
    }.flowOn(coroutineContext)
}
