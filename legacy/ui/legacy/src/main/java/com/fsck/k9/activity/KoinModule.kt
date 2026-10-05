package com.fsck.k9.activity

import com.fsck.k9.activity.compose.MessageComposeOperations
import com.fsck.k9.fragment.AttachmentProgressObserver
import com.fsck.k9.ui.messageview.AttachmentLoadingController
import com.fsck.k9.ui.messageview.DefaultAttachmentLoadingController
import org.koin.core.qualifier.named
import org.koin.dsl.module

val activityModule = module {
    single {
        MessageLoaderHelperFactory(
            messageViewInfoExtractorFactory = get(),
            messageReaderHtmlSettingsProvider = get(),
            messageComposerHtmlSettingsProvider = get(),
            localMessageReader = get(),
            messageBodyDownloader = get(),
        )
    }
    factory {
        MessageBodyDownloader(remoteContent = get(), appCoroutineScope = get(named("AppCoroutineScope")))
    }
    factory {
        AttachmentProgressObserver(remoteContent = get(), appCoroutineScope = get(named("AppCoroutineScope")))
    }
    factory {
        MessageComposeOperations(
            draftRepository = get(),
            outboxSender = get(),
            flagRepository = get(),
            mailSynchronizer = get(),
            appCoroutineScope = get(named("AppCoroutineScope")),
        )
    }
    factory<AttachmentLoadingController> {
        DefaultAttachmentLoadingController(remoteContent = get())
    }
}
