package com.fsck.k9.controller.push

import com.fsck.k9.backend.BackendManager
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.data.repository.PushFolderTrackingRepository
import net.thunderbird.feature.mail.folder.api.data.repository.PushFoldersQueryRepository
import net.thunderbird.feature.mail.sync.api.MailSynchronizer

internal class AccountPushControllerFactory(
    private val backendManager: BackendManager,
    private val mailSynchronizer: MailSynchronizer,
    private val pushFolderTrackingRepository: PushFolderTrackingRepository,
    private val pushFoldersQueryRepository: PushFoldersQueryRepository,
    private val logger: Logger,
) {
    fun create(accountId: AccountId): AccountPushController {
        return AccountPushController(
            backendManager,
            pushFoldersQueryRepository,
            backendPusherCallback = AccountBackendPusherCallback(
                mailSynchronizer = mailSynchronizer,
                pushFolderTrackingRepository = pushFolderTrackingRepository,
                accountId = accountId,
                logger = logger,
            ),
            accountId = accountId,
            logger = logger,
        )
    }
}
