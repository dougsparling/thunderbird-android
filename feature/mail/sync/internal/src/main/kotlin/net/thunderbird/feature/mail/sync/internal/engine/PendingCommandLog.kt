package net.thunderbird.feature.mail.sync.internal.engine

import com.fsck.k9.controller.MessagingControllerCommands.PendingCommand
import com.fsck.k9.mailstore.LocalStoreProvider
import net.thunderbird.core.android.account.LegacyAccountDto

/** The persistent log of an account's changes that still have to reach the server, oldest first. */
interface PendingCommandLog {
    fun getAll(account: LegacyAccountDto): List<PendingCommand>

    fun remove(account: LegacyAccountDto, command: PendingCommand)
}

/** [PendingCommandLog] in the account's database (`pending_commands` table). */
internal class LocalStorePendingCommandLog(
    private val localStoreProvider: LocalStoreProvider,
) : PendingCommandLog {
    override fun getAll(account: LegacyAccountDto): List<PendingCommand> {
        return localStoreProvider.getInstance(account).pendingCommands
    }

    override fun remove(account: LegacyAccountDto, command: PendingCommand) {
        localStoreProvider.getInstance(account).removePendingCommand(command)
    }
}
