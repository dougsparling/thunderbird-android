package com.fsck.k9.fragment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.thunderbird.feature.mail.sync.api.RemoteContentRepository

/** Reports the progress of attachment downloads to [AttachmentDownloadDialogFragment], which is Java code. */
class AttachmentProgressObserver(
    private val remoteContent: RemoteContentRepository,
    private val appCoroutineScope: CoroutineScope,
) {
    /** Calls [listener] on the main thread with the bytes downloaded so far, until the returned job is cancelled. */
    fun observe(listener: ProgressListener): Job {
        return appCoroutineScope.launch(Dispatchers.Main.immediate) {
            remoteContent.observeAttachmentProgress().collect(listener::onProgress)
        }
    }

    fun interface ProgressListener {
        fun onProgress(progress: Int)
    }
}
