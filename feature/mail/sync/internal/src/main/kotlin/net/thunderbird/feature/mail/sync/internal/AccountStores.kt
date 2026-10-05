package net.thunderbird.feature.mail.sync.internal

import app.k9mail.legacy.mailstore.MessageListRepository
import app.k9mail.legacy.mailstore.MessageStore
import app.k9mail.legacy.mailstore.MessageStoreManager
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.backend.BackendManager
import com.fsck.k9.backend.api.Backend
import com.fsck.k9.mailstore.LocalStore
import com.fsck.k9.mailstore.LocalStoreProvider
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.feature.account.AccountId

/**
 * The legacy accounts and what belongs to each of them: backend, local store and message store.
 *
 * The account manager hands out the same account instance every time, so changes made to it here are seen by everyone.
 */
@Suppress("TooManyFunctions")
internal class AccountStores(
    private val accountManager: LegacyAccountDtoManager,
    private val localStoreProvider: LocalStoreProvider,
    private val messageStoreManager: MessageStoreManager,
    private val backendManager: BackendManager,
    private val messageListRepository: MessageListRepository,
) {
    fun find(accountId: AccountId): LegacyAccountDto? = accountManager.getAccount(accountId.toString())

    fun find(accountUuid: String): LegacyAccountDto? = accountManager.getAccount(accountUuid)

    fun get(accountId: AccountId): LegacyAccountDto = find(accountId) ?: error("Account not found: $accountId")

    fun get(message: MessageReference): LegacyAccountDto = get(message.accountUuid)

    fun get(accountUuid: String): LegacyAccountDto = find(accountUuid) ?: error("Account not found: $accountUuid")

    fun getAll(): List<LegacyAccountDto> = accountManager.getAccounts()

    fun save(account: LegacyAccountDto) {
        accountManager.saveAccount(account)
    }

    fun backend(account: LegacyAccountDto): Backend = backendManager.getBackend(account.uuid)

    @Throws(MessagingException::class)
    fun localStore(account: LegacyAccountDto): LocalStore = localStoreProvider.getInstance(account)

    fun localStoreOrThrow(account: LegacyAccountDto): LocalStore {
        return try {
            localStoreProvider.getInstance(account)
        } catch (e: MessagingException) {
            throw IllegalStateException("Couldn't get LocalStore for account $account", e)
        }
    }

    fun messageStore(account: LegacyAccountDto): MessageStore = messageStoreManager.getMessageStore(account)

    fun folderServerId(account: LegacyAccountDto, folderId: Long): String {
        return messageStore(account).getFolderServerId(folderId)
            ?: throw IllegalStateException("Folder not found (ID: $folderId)")
    }

    fun folderId(account: LegacyAccountDto, folderServerId: String): Long {
        return messageStore(account).getFolderId(folderServerId)
            ?: throw IllegalStateException("Folder not found (server ID: $folderServerId)")
    }

    /**
     * Tells observers of the message store (folder counts, folder lists, the unread widget) that the folder's messages
     * changed. They reload everything of the account; [folderId] only says what changed.
     */
    @Suppress("UnusedParameter")
    fun notifyFolderChanged(account: LegacyAccountDto, folderId: Long) {
        messageListRepository.notifyMessageListChanged(account.uuid)
    }
}
