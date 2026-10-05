package app.k9mail.legacy.ui.folder

import app.k9mail.legacy.mailstore.FolderSettingsChangedListener
import app.k9mail.legacy.mailstore.FolderTypeMapper
import app.k9mail.legacy.mailstore.MessageListChangedListener
import app.k9mail.legacy.mailstore.MessageListRepository
import app.k9mail.legacy.mailstore.MessageStoreManager
import java.text.Collator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.mail.folder.api.Folder
import net.thunderbird.feature.mail.folder.FolderType
import net.thunderbird.feature.mail.folder.api.OutboxFolderManager
import com.fsck.k9.mail.FolderType as LegacyFolderType

class DefaultDisplayFolderRepository(
    private val accountManager: LegacyAccountDtoManager,
    private val messageListRepository: MessageListRepository,
    private val messageStoreManager: MessageStoreManager,
    private val outboxFolderManager: OutboxFolderManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DisplayFolderRepository {
    private val sortForDisplay =
        compareByDescending<DisplayFolder> { it.folder.type == FolderType.INBOX }
            .thenByDescending { it.folder.type == FolderType.OUTBOX }
            .thenByDescending { it.folder.type != FolderType.REGULAR }
            .thenByDescending { it.isInTopGroup }
            .thenBy(
                // #10718 use locale-sensitive ordering for folders
                Collator.getInstance().apply {
                    decomposition = Collator.CANONICAL_DECOMPOSITION
                },
            ) { it.folder.name }

    private fun getDisplayFolders(
        account: LegacyAccountDto,
        outboxFolderId: Long,
        includeHiddenFolders: Boolean,
    ): List<DisplayFolder> {
        val messageStore = messageStoreManager.getMessageStore(account.uuid)
        return messageStore.getDisplayFolders(
            includeHiddenFolders = includeHiddenFolders,
            outboxFolderId = outboxFolderId,
        ) { folder ->
            DisplayFolder(
                folder = Folder(
                    id = folder.id,
                    name = folder.name,
                    type = folder.takeIf { it.id == outboxFolderId }?.type?.toFolderType()
                        ?: FolderTypeMapper.folderTypeOf(account, folder.id),
                    isLocalOnly = folder.isLocalOnly,
                ),
                isInTopGroup = folder.isInTopGroup,
                unreadMessageCount = folder.unreadMessageCount,
                starredMessageCount = folder.starredMessageCount,
                pathDelimiter = account.folderPathDelimiter,
            )
        }.sortedWith(sortForDisplay)
    }

    override fun getDisplayFoldersFlow(
        account: LegacyAccountDto,
        includeHiddenFolders: Boolean,
    ): Flow<List<DisplayFolder>> {
        val messageStore = messageStoreManager.getMessageStore(account.uuid)

        // Listeners are called by whoever writes to the message store, so they only signal a change; the folders are
        // loaded here, once for any number of changes made while the previous load ran.
        return callbackFlow {
            val messageListChangedListener = MessageListChangedListener { trySend(Unit) }
            messageListRepository.addListener(account.uuid, messageListChangedListener)

            val folderSettingsChangedListener = FolderSettingsChangedListener { trySend(Unit) }
            messageStore.addFolderSettingsChangedListener(folderSettingsChangedListener)

            send(Unit)

            awaitClose {
                messageListRepository.removeListener(messageListChangedListener)
                messageStore.removeFolderSettingsChangedListener(folderSettingsChangedListener)
            }
        }.buffer(capacity = Channel.CONFLATED)
            .map {
                val outboxFolderId = outboxFolderManager.getOutboxFolderId(account.id)
                getDisplayFolders(account, outboxFolderId, includeHiddenFolders)
            }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
    }

    override fun getDisplayFoldersFlow(accountUuid: String): Flow<List<DisplayFolder>> {
        val account = accountManager.getAccount(accountUuid) ?: error("Account not found: $accountUuid")
        return getDisplayFoldersFlow(account, includeHiddenFolders = false)
    }

    private fun LegacyFolderType.toFolderType(): FolderType =
        when (this) {
            LegacyFolderType.REGULAR -> FolderType.REGULAR
            LegacyFolderType.INBOX -> FolderType.INBOX
            LegacyFolderType.OUTBOX -> FolderType.OUTBOX
            LegacyFolderType.DRAFTS -> FolderType.DRAFTS
            LegacyFolderType.SENT -> FolderType.SENT
            LegacyFolderType.TRASH -> FolderType.TRASH
            LegacyFolderType.SPAM -> FolderType.SPAM
            LegacyFolderType.ARCHIVE -> FolderType.ARCHIVE
        }
}
