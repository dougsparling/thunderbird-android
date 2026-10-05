package com.fsck.k9.notification

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.Preferences
import net.thunderbird.feature.mail.sync.api.MessageCapabilities
import net.thunderbird.feature.mail.sync.api.MessageDeleteRepository
import net.thunderbird.feature.mail.sync.api.MessageFlagRepository
import net.thunderbird.feature.mail.sync.api.MessageMoveRepository
import net.thunderbird.feature.mail.sync.api.NewMailNotifications
import com.fsck.k9.controller.MessageReferenceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.legacy.logging.Log
import net.thunderbird.core.preference.interaction.InteractionSettingsPreferenceManager
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

class NotificationActionService : Service() {
    private val preferences: Preferences by inject()
    private val flagRepository: MessageFlagRepository by inject()
    private val moveRepository: MessageMoveRepository by inject()
    private val deleteRepository: MessageDeleteRepository by inject()
    private val capabilities: MessageCapabilities by inject()
    private val newMailNotifications: NewMailNotifications by inject()
    private val coroutineScope: CoroutineScope by inject(named("AppCoroutineScope"))
    private val interactionPreferences: InteractionSettingsPreferenceManager by inject()
    private val interactionSettings get() = interactionPreferences.getConfig()

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i("NotificationActionService started with startId = %d", startId)

        startHandleCommand(intent, startId)

        return START_NOT_STICKY
    }

    private fun startHandleCommand(intent: Intent, startId: Int) {
        coroutineScope.launch(Dispatchers.IO) {
            handleCommand(intent)
            stopSelf(startId)
        }
    }

    private suspend fun handleCommand(intent: Intent) {
        val accountUuid = intent.getStringExtra(EXTRA_ACCOUNT_UUID)
        if (accountUuid == null) {
            Log.w("Missing account UUID.")
            return
        }

        val account = preferences.getAccount(accountUuid)
        if (account == null) {
            Log.w("Could not find account for notification action.")
            return
        }

        when (intent.action) {
            ACTION_MARK_AS_READ -> markMessagesAsRead(intent)
            ACTION_DELETE -> deleteMessages(intent)
            ACTION_ARCHIVE -> archiveMessages(intent)
            ACTION_SPAM -> markMessageAsSpam(intent, account)
            ACTION_STAR -> markMessagesAsStarred(intent)
            ACTION_DISMISS -> Log.i("Notification dismissed")
        }

        cancelNotifications(intent, account)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private suspend fun markMessagesAsRead(intent: Intent) {
        Log.i("NotificationActionService marking messages as read")

        val messageReferenceStrings = intent.getStringArrayListExtra(EXTRA_MESSAGE_REFERENCES)
        val messageReferences = MessageReferenceHelper.toMessageReferenceList(messageReferenceStrings)

        for (messageReference in messageReferences) {
            flagRepository.update(messageReference, Flag.SEEN, true)
        }
    }

    private suspend fun deleteMessages(intent: Intent) {
        Log.i("NotificationActionService deleting messages")

        val messageReferenceStrings = intent.getStringArrayListExtra(EXTRA_MESSAGE_REFERENCES)
        val messageReferences = MessageReferenceHelper.toMessageReferenceList(messageReferenceStrings)

        deleteRepository.delete(messageReferences)
    }

    private suspend fun archiveMessages(intent: Intent) {
        Log.i("NotificationActionService archiving messages")

        val messageReferenceStrings = intent.getStringArrayListExtra(EXTRA_MESSAGE_REFERENCES)
        val messageReferences = MessageReferenceHelper.toMessageReferenceList(messageReferenceStrings)

        moveRepository.archive(messageReferences)
    }

    private suspend fun markMessageAsSpam(intent: Intent, account: LegacyAccountDto) {
        Log.i("NotificationActionService moving messages to spam")

        val messageReferenceString = intent.getStringExtra(EXTRA_MESSAGE_REFERENCE)
        val messageReference = MessageReference.parse(messageReferenceString)

        if (messageReference == null) {
            Log.w("Invalid message reference: %s", messageReferenceString)
            return
        }

        val spamFolderId = account.spamFolderId
        if (spamFolderId == null) {
            Log.w("No spam folder configured")
            return
        }

        if (!interactionSettings.isConfirmSpam && capabilities.isMoveCapable(account.id)) {
            val sourceFolderId = messageReference.folderId
            moveRepository.move(account.id, sourceFolderId, listOf(messageReference), spamFolderId)
        }
    }

    private suspend fun markMessagesAsStarred(intent: Intent) {
        Log.i("NotificationActionService starring messages")

        val messageReferenceStrings = intent.getStringArrayListExtra(EXTRA_MESSAGE_REFERENCES)
        val messageReferences = MessageReferenceHelper.toMessageReferenceList(messageReferenceStrings)

        for (messageReference in messageReferences) {
            flagRepository.update(messageReference, Flag.FLAGGED, true)
        }
    }

    private fun cancelNotifications(intent: Intent, account: LegacyAccountDto) {
        if (intent.hasExtra(EXTRA_MESSAGE_REFERENCE)) {
            val messageReferenceString = intent.getStringExtra(EXTRA_MESSAGE_REFERENCE)
            val messageReference = MessageReference.parse(messageReferenceString)

            if (messageReference != null) {
                newMailNotifications.clearForMessage(messageReference)
            } else {
                Log.w("Invalid message reference: %s", messageReferenceString)
            }
        } else if (intent.hasExtra(EXTRA_MESSAGE_REFERENCES)) {
            val messageReferenceStrings = intent.getStringArrayListExtra(EXTRA_MESSAGE_REFERENCES)
            val messageReferences = MessageReferenceHelper.toMessageReferenceList(messageReferenceStrings)

            for (messageReference in messageReferences) {
                newMailNotifications.clearForMessage(messageReference)
            }
        } else {
            newMailNotifications.clearForAccount(account.id)
        }
    }
}
