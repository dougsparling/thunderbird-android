package com.fsck.k9.controller

import app.k9mail.legacy.message.controller.MessageCountsProvider
import org.koin.dsl.module

val controllerModule = module {
    single<MessageCountsProvider> {
        DefaultMessageCountsProvider(
            accountManager = get(),
            messageStoreManager = get(),
            messageListRepository = get(),
            outboxFolderManager = get(),
        )
    }
}
