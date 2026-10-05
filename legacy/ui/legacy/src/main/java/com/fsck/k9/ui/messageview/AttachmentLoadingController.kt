package com.fsck.k9.ui.messageview

import com.fsck.k9.mail.Part

interface AttachmentLoadingController {
    /**
     * Downloads the content of [part], a part of a stored message, and stores it with the message.
     *
     * @return whether the content was downloaded.
     */
    suspend fun loadAttachment(part: Part): Boolean
}
