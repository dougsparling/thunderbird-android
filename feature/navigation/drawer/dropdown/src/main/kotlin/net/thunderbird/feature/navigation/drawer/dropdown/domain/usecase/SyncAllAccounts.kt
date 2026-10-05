package net.thunderbird.feature.navigation.drawer.dropdown.domain.usecase

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import net.thunderbird.feature.mail.sync.api.MailSynchronizer
import net.thunderbird.feature.navigation.drawer.dropdown.domain.DomainContract.UseCase

class SyncAllAccounts(
    private val mailSynchronizer: MailSynchronizer,
    private val coroutineContext: CoroutineContext = Dispatchers.IO,
) : UseCase.SyncAllAccounts {
    override fun invoke(): Flow<Result<Unit>> = flow {
        mailSynchronizer.checkMail(
            accountId = null,
            ignoreLastCheckedTime = true,
            useManualWakeLock = true,
            notify = true,
        )

        emit(Result.success(Unit))
    }.flowOn(coroutineContext)
}
