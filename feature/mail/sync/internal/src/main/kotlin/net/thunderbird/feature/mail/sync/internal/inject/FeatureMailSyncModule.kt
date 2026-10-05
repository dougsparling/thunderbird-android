package net.thunderbird.feature.mail.sync.internal.inject

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
import net.thunderbird.feature.mail.sync.internal.AccountStores
import net.thunderbird.feature.mail.sync.internal.DefaultMailSynchronizer
import net.thunderbird.feature.mail.sync.internal.DefaultMessageCapabilities
import net.thunderbird.feature.mail.sync.internal.DefaultMessageDeleteRepository
import net.thunderbird.feature.mail.sync.internal.DefaultMessageDraftRepository
import net.thunderbird.feature.mail.sync.internal.DefaultMessageFlagRepository
import net.thunderbird.feature.mail.sync.internal.DefaultMessageMoveRepository
import net.thunderbird.feature.mail.sync.internal.DefaultNewMailNotifications
import net.thunderbird.feature.mail.sync.internal.DefaultOutboxSender
import net.thunderbird.feature.mail.sync.internal.DefaultRemoteContentRepository
import net.thunderbird.feature.mail.sync.internal.LocalMessages
import net.thunderbird.feature.mail.sync.internal.MessageMover
import net.thunderbird.feature.mail.sync.internal.PendingCommandProcessor
import net.thunderbird.feature.mail.sync.internal.PendingCommandQueue
import net.thunderbird.feature.mail.sync.internal.ServerErrorNotifier
import net.thunderbird.feature.mail.sync.internal.SyncEventBus
import net.thunderbird.feature.mail.sync.internal.engine.LocalStorePendingCommandLog
import net.thunderbird.feature.mail.sync.internal.engine.PendingCommandLog
import net.thunderbird.feature.mail.sync.internal.engine.PendingCommandReplay
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import org.koin.core.qualifier.named
import org.koin.dsl.bind
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

    single {
        AccountStores(
            accountManager = get(),
            localStoreProvider = get(),
            messageStoreManager = get(),
            backendManager = get(),
            messageListRepository = get(),
        )
    }
    single { SyncEventBus() }
    single {
        ServerErrorNotifier(
            accounts = get(),
            notificationController = get(),
            notificationManager = get(),
            featureFlagProvider = get(),
            logger = get(),
            mainImmediateDispatcher = Dispatchers.Main.immediate,
        )
    }
    single {
        PendingCommandProcessor(
            accounts = get(),
            events = get(),
            serializer = get(),
            localMessageUidPrefixProvider = get(),
            logger = get(),
            isDebug = BuildConfig.DEBUG,
        )
    }
    single {
        PendingCommandQueue(
            accounts = get(),
            serializer = get(),
            replay = get(),
            processor = get(),
            serverErrorNotifier = get(),
            logger = get(),
        )
    }
    single { LocalMessages(accounts = get(), logger = get()) }
    single {
        MessageMover(
            accounts = get(),
            localMessages = get(),
            pendingCommands = get(),
            localMessageUidPrefixProvider = get(),
            logger = get(),
        )
    }

    single<MessageCapabilities> {
        DefaultMessageCapabilities(accounts = get(), localMessageUidPrefixProvider = get())
    }
    single<MessageFlagRepository> {
        DefaultMessageFlagRepository(
            accounts = get(),
            localMessages = get(),
            pendingCommands = get(),
            serializer = get(),
            notificationController = get(),
            localMessageReader = get(),
            appScope = get(named("AppCoroutineScope")),
            ioDispatcher = Dispatchers.IO,
            logger = get(),
        )
    }
    single {
        DefaultMessageDeleteRepository(
            accounts = get(),
            localMessages = get(),
            messageMover = get(),
            pendingCommands = get(),
            serializer = get(),
            notificationController = get(),
            localDeleteOperationDecider = get(),
            outboxFolderManager = get(),
            localMessageUidPrefixProvider = get(),
            logger = get(),
        )
    } bind MessageDeleteRepository::class
    single {
        DefaultMessageDraftRepository(
            accounts = get(),
            pendingCommands = get(),
            deleteRepository = get(),
            saveMessageDataCreator = get(),
            logger = get(),
        )
    } bind MessageDraftRepository::class
    single<MessageMoveRepository> {
        DefaultMessageMoveRepository(
            accounts = get(),
            localMessages = get(),
            messageMover = get(),
            draftRepository = get(),
            serializer = get(),
            localMessageReader = get(),
            featureFlagProvider = get(),
            logger = get(),
        )
    }
    single<NewMailNotifications> {
        DefaultNewMailNotifications(
            accounts = get(),
            notificationController = get(),
            serializer = get(),
            events = get(),
        )
    }
    single<RemoteContentRepository> {
        DefaultRemoteContentRepository(
            accounts = get(),
            serializer = get(),
            serverErrorNotifier = get(),
            localMessageUidPrefixProvider = get(),
            ioDispatcher = Dispatchers.IO,
            logger = get(),
            syncDebugLogger = get(named("syncDebug")),
        )
    }
    single {
        DefaultOutboxSender(
            accounts = get(),
            serializer = get(),
            pendingCommands = get(),
            events = get(),
            serverErrorNotifier = get(),
            notificationController = get(),
            outboxFolderManager = get(),
            saveMessageDataCreator = get(),
            ioDispatcher = Dispatchers.IO,
            logger = get(),
        )
    } bind OutboxSender::class
    single<MailSynchronizer> {
        DefaultMailSynchronizer(
            accounts = get(),
            serializer = get(),
            pendingCommands = get(),
            events = get(),
            serverErrorNotifier = get(),
            outboxSender = get(),
            localMessages = get(),
            notificationController = get(),
            notificationStrategy = get(),
            powerManager = get(),
            clock = get(),
            logger = get(),
            syncDebugLogger = get(named("syncDebug")),
        )
    }
}
