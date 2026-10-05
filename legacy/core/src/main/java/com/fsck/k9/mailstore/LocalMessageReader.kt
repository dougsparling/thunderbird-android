package com.fsck.k9.mailstore

import com.fsck.k9.mail.FetchProfile
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException

/** Loads a stored message from the local store. */
class LocalMessageReader(private val localStoreProvider: LocalStoreProvider) {
    /**
     * Loads the message with its body.
     *
     * @throws IllegalArgumentException if the message doesn't exist.
     */
    @Throws(MessagingException::class)
    fun loadMessage(account: LegacyAccountDto, folderId: Long, uid: String): LocalMessage {
        return load(account, folderId, uid, FetchProfile.Item.BODY)
    }

    /**
     * Loads the message's envelope (headers and flags) only.
     *
     * @throws IllegalArgumentException if the message doesn't exist.
     */
    @Throws(MessagingException::class)
    fun loadMessageMetadata(account: LegacyAccountDto, folderId: Long, uid: String): LocalMessage {
        return load(account, folderId, uid, FetchProfile.Item.ENVELOPE)
    }

    private fun load(account: LegacyAccountDto, folderId: Long, uid: String, item: FetchProfile.Item): LocalMessage {
        val localStore = localStoreProvider.getInstance(account)
        val localFolder = localStore.getFolder(folderId)
        localFolder.open()

        val message = localFolder.getMessage(uid)
        if (message == null || message.databaseId == 0L) {
            throw IllegalArgumentException("Message not found: folder=${localFolder.name}, uid=$uid")
        }

        val fetchProfile = FetchProfile()
        fetchProfile.add(item)
        localFolder.fetch(listOf(message), fetchProfile, null)

        return message
    }
}
