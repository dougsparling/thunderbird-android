package net.thunderbird.feature.mail.sync.internal.engine

import com.fsck.k9.controller.ControllerEngine
import com.fsck.k9.controller.PendingCommandExecutor
import net.thunderbird.core.android.account.LegacyAccountDto

/** Runs the legacy controller's work on [RemoteWorkSerializer] and its pending commands with [PendingCommandReplay]. */
internal class SerializerControllerEngine(
    private val serializer: RemoteWorkSerializer,
    private val pendingCommandReplay: PendingCommandReplay,
) : ControllerEngine {
    override fun enqueue(description: String, isForeground: Boolean, runnable: Runnable) {
        val priority = if (isForeground) WorkPriority.FOREGROUND else WorkPriority.BACKGROUND
        serializer.enqueue(description, priority) { runnable.run() }
    }

    override fun processPendingCommands(account: LegacyAccountDto, executor: PendingCommandExecutor) {
        pendingCommandReplay.replay(account, executor)
    }
}
