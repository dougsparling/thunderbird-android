package net.thunderbird.feature.mail.sync.internal

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.mailstore.LocalFolder
import com.fsck.k9.mailstore.LocalMessage
import com.fsck.k9.mailstore.MessageListCache
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.core.logging.Logger

private const val TAG = "LocalMessages"

/**
 * Loads the local messages that [MessageReference]s point to, and the threads they belong to.
 *
 * Also keeps the [MessageListCache] up to date: it hides moved and deleted messages and shows changed flags until the
 * change is written to the database.
 */
internal class LocalMessages(
    private val accounts: AccountStores,
    private val logger: Logger,
) {
    /** Calls [action] for each folder's messages among [messages], which may belong to different accounts. */
    fun forEachFolder(
        messages: List<MessageReference?>,
        action: (account: LegacyAccountDto, folder: LocalFolder, messages: List<LocalMessage>) -> Unit,
    ) {
        val accountMap = messages.filterNotNull()
            .groupBy { it.accountUuid }
            .mapValues { (_, accountMessages) -> accountMessages.groupBy { it.folderId } }

        for ((accountUuid, folderMap) in accountMap) {
            val account = accounts.get(accountUuid)
            for ((folderId, messageList) in folderMap) {
                forFolder(account, folderId, messageList, action)
            }
        }
    }

    /** Calls [action] with the local messages [messageReferences] point to, which are in the folder [folderId]. */
    fun forFolder(
        account: LegacyAccountDto,
        folderId: Long,
        messageReferences: List<MessageReference>,
        action: (account: LegacyAccountDto, folder: LocalFolder, messages: List<LocalMessage>) -> Unit,
    ) {
        try {
            val messageFolder = accounts.localStore(account).getFolder(folderId)
            val localMessages = messageFolder.getMessagesByReference(messageReferences)
            action(account, messageFolder, localMessages)
        } catch (e: MessagingException) {
            logger.error(TAG, e) { "Error loading account?!" }
        }
    }

    /** All messages of the threads [messages] belong to. */
    @Throws(MessagingException::class)
    fun collectMessagesInThreads(account: LegacyAccountDto, messages: List<LocalMessage>): List<LocalMessage> {
        val localStore = accounts.localStore(account)

        return messages.flatMap { localMessage ->
            val rootId = localMessage.rootId
            val threadId = if (rootId == -1L) localMessage.threadId else rootId

            localStore.getMessagesInThread(threadId)
        }
    }

    fun hide(account: LegacyAccountDto, messages: List<LocalMessage>) {
        cache(account).hideMessages(messages)
    }

    fun unhide(account: LegacyAccountDto, messages: List<LocalMessage>) {
        cache(account).unhideMessages(messages)
    }

    fun isHidden(message: LocalMessage): Boolean {
        val messageId = message.databaseId
        val folderId = message.folder.databaseId

        return MessageListCache.getCache(message.folder.accountUuid).isMessageHidden(messageId, folderId)
    }

    fun setFlagInCache(account: LegacyAccountDto, messageIds: List<Long>, flag: Flag, newState: Boolean) {
        cache(account).setFlagForMessages(messageIds, flag, newState)
    }

    fun removeFlagFromCache(account: LegacyAccountDto, messageIds: List<Long>, flag: Flag) {
        cache(account).removeFlagForMessages(messageIds, flag)
    }

    fun setFlagForThreadsInCache(account: LegacyAccountDto, threadRootIds: List<Long>, flag: Flag, newState: Boolean) {
        cache(account).setValueForThreads(threadRootIds, flag, newState)
    }

    fun removeFlagForThreadsFromCache(account: LegacyAccountDto, threadRootIds: List<Long>, flag: Flag) {
        cache(account).removeFlagForThreads(threadRootIds, flag)
    }

    private fun cache(account: LegacyAccountDto) = MessageListCache.getCache(account.uuid)
}
