package net.thunderbird.feature.mail.sync.internal

import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.notification.NotificationController
import com.fsck.k9.search.isNewMessages
import com.fsck.k9.search.isSingleFolder
import com.fsck.k9.search.isUnifiedFolders
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.NewMailNotifications
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import net.thunderbird.feature.mail.sync.internal.engine.WorkPriority
import net.thunderbird.feature.search.legacy.LocalMessageSearch

internal class DefaultNewMailNotifications(
    private val accounts: AccountStores,
    private val notificationController: NotificationController,
    private val serializer: RemoteWorkSerializer,
    private val events: SyncEventBus,
) : NewMailNotifications {
    override fun clearForMessageList(search: LocalMessageSearch) {
        serializer.enqueue("clearNotifications", WorkPriority.FOREGROUND) {
            clearNotifications(search)
        }
    }

    override fun clearForAccount(accountId: AccountId) {
        notificationController.clearNewMailNotifications(accounts.get(accountId), clearNewMessageState = true)
    }

    override fun clearForMessage(message: MessageReference) {
        notificationController.removeNewMailNotification(accounts.get(message), message)
    }

    override fun onAccountRemoved(accountId: AccountId) {
        notificationController.clearNewMailNotifications(accounts.get(accountId), clearNewMessageState = false)
        events.forgetAccount(accountId)
    }

    private fun clearNotifications(search: LocalMessageSearch) {
        if (search.isUnifiedFolders) {
            clearUnifiedFoldersNotifications()
        } else if (search.isNewMessages) {
            clearAllNotifications()
        } else if (search.isSingleFolder) {
            val account = accounts.find(search.accountUuids.first()) ?: return
            val folderId = search.folderIds.first()
            clearNotifications(account, folderId)
        } else {
            // TODO: Remove notifications when updating the message list. That way we can easily remove only
            //  notifications for messages that are currently displayed in the list.
        }
    }

    private fun clearUnifiedFoldersNotifications() {
        for (account in accounts.getAll()) {
            val messageStore = accounts.messageStore(account)

            val folderIds = messageStore.getFolders(excludeLocalOnly = true) { folderDetails ->
                if (folderDetails.isIntegrate) folderDetails.id else null
            }.filterNotNull().toSet()

            if (folderIds.isNotEmpty()) {
                notificationController.clearNewMailNotifications(account) { messageReferences ->
                    messageReferences.filter { messageReference -> messageReference.folderId in folderIds }
                }
            }
        }
    }

    private fun clearAllNotifications() {
        for (account in accounts.getAll()) {
            notificationController.clearNewMailNotifications(account, clearNewMessageState = false)
        }
    }

    private fun clearNotifications(account: LegacyAccountDto, folderId: Long) {
        notificationController.clearNewMailNotifications(account) { messageReferences ->
            messageReferences.filter { messageReference -> messageReference.folderId == folderId }
        }
    }
}
