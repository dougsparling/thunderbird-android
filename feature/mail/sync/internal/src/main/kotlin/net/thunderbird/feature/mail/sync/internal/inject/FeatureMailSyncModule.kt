package net.thunderbird.feature.mail.sync.internal.inject

import com.fsck.k9.controller.ControllerEngine
import com.fsck.k9.core.BuildConfig
import kotlinx.coroutines.Dispatchers
import net.thunderbird.feature.mail.sync.api.MailSynchronizer
import net.thunderbird.feature.mail.sync.api.MessageCapabilities
import net.thunderbird.feature.mail.sync.api.MessageDeleteRepository
import net.thunderbird.feature.mail.sync.api.MessageDraftRepository
import net.thunderbird.feature.mail.sync.api.MessageFlagRepository
import net.thunderbird.feature.mail.sync.api.MessageMoveRepository
import net.thunderbird.feature.mail.sync.api.NewMailNotifications
import net.thunderbird.feature.mail.sync.api.OutboxSender
import net.thunderbird.feature.mail.sync.api.RemoteContentRepository
import net.thunderbird.feature.mail.sync.internal.engine.LocalStorePendingCommandLog
import net.thunderbird.feature.mail.sync.internal.engine.PendingCommandLog
import net.thunderbird.feature.mail.sync.internal.engine.PendingCommandReplay
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import net.thunderbird.feature.mail.sync.internal.engine.SerializerControllerEngine
import net.thunderbird.feature.mail.sync.internal.legacy.ControllerMailSynchronizer
import net.thunderbird.feature.mail.sync.internal.legacy.ControllerMessageCapabilities
import net.thunderbird.feature.mail.sync.internal.legacy.ControllerMessageDeleteRepository
import net.thunderbird.feature.mail.sync.internal.legacy.ControllerMessageDraftRepository
import net.thunderbird.feature.mail.sync.internal.legacy.ControllerMessageFlagRepository
import net.thunderbird.feature.mail.sync.internal.legacy.ControllerMessageMoveRepository
import net.thunderbird.feature.mail.sync.internal.legacy.ControllerNewMailNotifications
import net.thunderbird.feature.mail.sync.internal.legacy.ControllerOutboxSender
import net.thunderbird.feature.mail.sync.internal.legacy.ControllerRemoteContentRepository
import net.thunderbird.feature.mail.sync.internal.legacy.LegacyAccounts
import org.koin.dsl.module

val featureMailSyncModule = module {
    single { RemoteWorkSerializer(logger = get()) }

    single<PendingCommandLog> { LocalStorePendingCommandLog(localStoreProvider = get()) }

    single {
        PendingCommandReplay(
            pendingCommandLog = get(),
            logger = get(),
            isDebug = BuildConfig.DEBUG,
        )
    }

    single<ControllerEngine> {
        SerializerControllerEngine(
            serializer = get(),
            pendingCommandReplay = get(),
        )
    }

    single { LegacyAccounts(accountManager = get()) }
    single<MessageCapabilities> { ControllerMessageCapabilities(controller = get(), accounts = get()) }
    single<MessageFlagRepository> { ControllerMessageFlagRepository(controller = get(), accounts = get()) }
    single<MessageMoveRepository> { ControllerMessageMoveRepository(controller = get(), accounts = get()) }
    single<MessageDeleteRepository> { ControllerMessageDeleteRepository(controller = get(), accounts = get()) }
    single<MessageDraftRepository> { ControllerMessageDraftRepository(controller = get(), accounts = get()) }
    single<NewMailNotifications> { ControllerNewMailNotifications(controller = get(), accounts = get()) }
    single<RemoteContentRepository> { ControllerRemoteContentRepository(controller = get(), accounts = get()) }
    single<OutboxSender> {
        ControllerOutboxSender(controller = get(), accounts = get(), ioDispatcher = Dispatchers.IO)
    }
    single<MailSynchronizer> {
        ControllerMailSynchronizer(controller = get(), accounts = get(), ioDispatcher = Dispatchers.IO)
    }
}
