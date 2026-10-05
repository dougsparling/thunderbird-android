package com.fsck.k9.activity.compose

import androidx.annotation.WorkerThread
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.mail.Message
import com.fsck.k9.ui.helper.launchUserChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.sync.api.MailSynchronizer
import net.thunderbird.feature.mail.sync.api.MessageDraftRepository
import net.thunderbird.feature.mail.sync.api.MessageFlagRepository
import net.thunderbird.feature.mail.sync.api.OutboxSender
import net.thunderbird.feature.mail.sync.api.SyncEvent

/** Saves, sends and discards messages for `MessageCompose`, which is Java code. */
class MessageComposeOperations(
    private val draftRepository: MessageDraftRepository,
    private val outboxSender: OutboxSender,
    private val flagRepository: MessageFlagRepository,
    private val mailSynchronizer: MailSynchronizer,
    private val appCoroutineScope: CoroutineScope,
) {
    /** See [MessageDraftRepository.save]. */
    @WorkerThread
    fun saveDraft(
        account: LegacyAccountDto,
        message: Message,
        existingDraftId: Long?,
        plaintextSubject: String?,
    ): Long? {
        return runBlocking { draftRepository.save(account.id, message, existingDraftId, plaintextSubject) }
    }

    /** Deletes the draft, see [MessageDraftRepository.delete]. */
    fun deleteDraft(account: LegacyAccountDto, draftId: Long) {
        appCoroutineScope.launchUserChange { draftRepository.delete(account.id, draftId) }
    }

    /** Sends [message], then deletes the draft it was written as, if any. */
    @WorkerThread
    fun send(account: LegacyAccountDto, message: Message, plaintextSubject: String?, draftId: Long?) {
        runBlocking {
            outboxSender.send(account.id, message, plaintextSubject)
            if (draftId != null) {
                draftRepository.deleteSkippingTrash(account.id, draftId)
            }
        }
    }

    /** Sets [flag] on the message that was replied to or forwarded. */
    @WorkerThread
    fun addFlag(message: MessageReference, flag: Flag) {
        runBlocking { flagRepository.update(message, flag, newState = true) }
    }

    /**
     * Calls [listener] on the main thread when a message created in the app gets its ID from the server, until the
     * returned job is cancelled.
     */
    fun observeMessageUidChanges(listener: MessageUidChangeListener): Job {
        return appCoroutineScope.launch(Dispatchers.Main.immediate) {
            mailSynchronizer.observeEvents()
                .filterIsInstance<SyncEvent.MessageUidChanged>()
                .collect { event ->
                    listener.onMessageUidChanged(event.accountId.toString(), event.folderId, event.oldUid, event.newUid)
                }
        }
    }

    fun interface MessageUidChangeListener {
        fun onMessageUidChanged(accountUuid: String, folderId: Long, oldUid: String, newUid: String)
    }
}
