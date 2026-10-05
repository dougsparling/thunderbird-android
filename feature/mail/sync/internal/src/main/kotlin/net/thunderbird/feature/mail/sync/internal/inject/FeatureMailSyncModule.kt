package net.thunderbird.feature.mail.sync.internal.inject

import com.fsck.k9.controller.ControllerEngine
import com.fsck.k9.core.BuildConfig
import net.thunderbird.feature.mail.sync.internal.engine.LocalStorePendingCommandLog
import net.thunderbird.feature.mail.sync.internal.engine.PendingCommandLog
import net.thunderbird.feature.mail.sync.internal.engine.PendingCommandReplay
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import net.thunderbird.feature.mail.sync.internal.engine.SerializerControllerEngine
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
}
