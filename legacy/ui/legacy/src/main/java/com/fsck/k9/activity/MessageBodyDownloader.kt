package com.fsck.k9.activity

import app.k9mail.legacy.message.controller.MessageReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.feature.mail.sync.api.DownloadError
import net.thunderbird.feature.mail.sync.api.RemoteContentRepository

/**
 * Downloads the body of a message for [MessageLoaderHelper], which is Java code.
 *
 * The download is queued before [download] returns. The callback is called on a background thread.
 */
class MessageBodyDownloader(
    private val remoteContent: RemoteContentRepository,
    private val appCoroutineScope: CoroutineScope,
) {
    fun download(message: MessageReference, complete: Boolean, callback: Callback) {
        appCoroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val outcome = if (complete) {
                remoteContent.downloadComplete(message)
            } else {
                remoteContent.downloadPartial(message)
            }

            when (outcome) {
                is Outcome.Success -> callback.onDownloadFinished(message)
                is Outcome.Failure -> when (outcome.error) {
                    DownloadError.MessageNotFound -> callback.onMessageNotFound()
                    is DownloadError.Failed -> callback.onDownloadFailed()
                }
            }
        }
    }

    interface Callback {
        fun onDownloadFinished(message: MessageReference)
        fun onMessageNotFound()
        fun onDownloadFailed()
    }
}
