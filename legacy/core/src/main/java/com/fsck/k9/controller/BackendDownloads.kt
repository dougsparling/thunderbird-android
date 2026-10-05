package com.fsck.k9.controller

import com.fsck.k9.backend.api.Backend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import net.thunderbird.core.common.exception.MessagingException

@Throws(MessagingException::class)
internal fun Backend.downloadCompleteMessageBlocking(
    ioDispatcher: CoroutineDispatcher,
    folderServerId: String,
    messageServerId: String,
) {
    runBlocking(ioDispatcher) {
        downloadCompleteMessage(folderServerId, messageServerId)
    }
}
