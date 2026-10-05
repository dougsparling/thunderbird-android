package net.thunderbird.feature.mail.sync.internal

import com.fsck.k9.backend.api.SyncConfig
import net.thunderbird.core.android.account.AccountDefaultsProvider
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.mail.Flag

/** The flags that are synced with the server. */
internal val SYNC_FLAGS: Set<Flag> = setOf(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED, Flag.FORWARDED)

/** How the backend syncs the account's folders and downloads messages, from the account's settings. */
internal fun createSyncConfig(account: LegacyAccountDto): SyncConfig {
    return SyncConfig(
        expungePolicy = account.expungePolicy.toBackendExpungePolicy(),
        earliestPollDate = account.earliestPollDate,
        syncRemoteDeletions = account.isSyncRemoteDeletions,
        maximumAutoDownloadMessageSize = account.maximumAutoDownloadMessageSize,
        defaultVisibleLimit = AccountDefaultsProvider.DEFAULT_VISIBLE_LIMIT,
        syncFlags = SYNC_FLAGS,
    )
}
