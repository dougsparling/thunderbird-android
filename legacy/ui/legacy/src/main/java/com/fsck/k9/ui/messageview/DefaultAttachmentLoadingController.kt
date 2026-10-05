package com.fsck.k9.ui.messageview

import com.fsck.k9.mail.Part
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.feature.mail.sync.api.RemoteContentRepository

class DefaultAttachmentLoadingController(
    private val remoteContent: RemoteContentRepository,
) : AttachmentLoadingController {
    override suspend fun loadAttachment(part: Part): Boolean {
        return remoteContent.downloadAttachment(part) is Outcome.Success
    }
}
