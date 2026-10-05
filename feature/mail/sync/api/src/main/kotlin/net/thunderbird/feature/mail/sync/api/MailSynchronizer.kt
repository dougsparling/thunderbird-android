package net.thunderbird.feature.mail.sync.api

import kotlinx.coroutines.flow.Flow
import net.thunderbird.feature.account.AccountId

/**
 * Brings the app's copy of the accounts' folders and messages up to date with the servers.
 *
 * Work with the servers runs in the background, one piece at a time for all accounts. Functions named `request...`
 * only queue work and return right away; `suspend` functions return once their work is done. Progress is reported
 * through [observeEvents].
 */
interface MailSynchronizer {
    /** Queues a sync of one folder. Pull to refresh doesn't [notify] of new mail. */
    fun requestFolderSync(accountId: AccountId, folderId: Long, notify: Boolean)

    /** Queues downloading more of the folder's older messages than the account shows by default. */
    fun requestMoreMessages(accountId: AccountId, folderId: Long)

    /** Queues a refresh of the account's folder list, ahead of all background work. */
    fun requestFolderListRefresh(accountId: AccountId)

    /** Refreshes the account's folder list, as background work. */
    suspend fun refreshFolderList(accountId: AccountId)

    /**
     * Queues a mail check: sending pending messages, then syncing every visible folder that has sync enabled, of one
     * account or, if [accountId] is `null`, of all accounts. Unless [ignoreLastCheckedTime], folders checked within the
     * account's check interval are skipped. With [useManualWakeLock] the device stays awake until the check is done.
     */
    fun requestCheckMail(
        accountId: AccountId?,
        ignoreLastCheckedTime: Boolean,
        useManualWakeLock: Boolean,
        notify: Boolean,
    )

    /** Like [requestCheckMail], but returns once the mail check is done. */
    suspend fun checkMail(
        accountId: AccountId?,
        ignoreLastCheckedTime: Boolean,
        useManualWakeLock: Boolean,
        notify: Boolean,
    )

    /**
     * The scheduled mail check: [checkMail] for the account, notifying of new mail, and recording when the account
     * was last synced if it worked.
     *
     * @return `false` if syncing a folder failed, so the check should be retried.
     */
    suspend fun syncPeriodically(accountId: AccountId): Boolean

    /** Syncs a folder the server reported changes for (push), notifying of new mail. */
    suspend fun syncPushedFolder(accountId: AccountId, folderServerId: String)

    /** Tells the user about [exception] if they need to act (failed authentication, untrusted certificate). */
    fun reportError(accountId: AccountId, exception: Exception)

    /**
     * Notifies the user if the account can't sign in to its servers (missing password, OAuth sign-in needed), and
     * removes such notifications for OAuth accounts that can.
     */
    fun checkAuthenticationProblem(accountId: AccountId)

    /**
     * Progress of the background work, for all accounts.
     *
     * A new collector first gets the current state of folder syncs: the outcome of each folder's last sync and the
     * progress of one that's running, if any.
     */
    fun observeEvents(): Flow<SyncEvent>
}
