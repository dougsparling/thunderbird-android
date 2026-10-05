package net.thunderbird.feature.mail.sync.internal.engine

import com.fsck.k9.controller.PendingCommandExecutor
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.logging.Logger

private const val TAG = "PendingCommandReplay"

/**
 * Replays an account's pending commands: changes made locally that still have to reach the server.
 *
 * Commands run oldest first. A command that succeeds or fails permanently is removed. A temporary failure (e.g. the
 * server can't be reached) stops the replay and is rethrown; that command and all later ones stay pending, so they're
 * retried in the same order next time. Any other exception means the command can't be carried out: it's removed, and
 * in debug builds ([isDebug]) the failure is turned into an [AssertionError].
 *
 * TODO: When a command is removed because of an error, its local changes stay, and commands depending on it still
 *  run. The user isn't told either.
 */
class PendingCommandReplay(
    private val pendingCommandLog: PendingCommandLog,
    private val logger: Logger,
    private val isDebug: Boolean,
) {
    @Suppress("TooGenericExceptionCaught")
    @Throws(MessagingException::class)
    fun replay(account: LegacyAccountDto, executor: PendingCommandExecutor) {
        for (command in pendingCommandLog.getAll(account)) {
            val commandName = command.commandName
            logger.debug(TAG) { "Processing pending command '$commandName'" }

            try {
                executor.execute(command, account)
                pendingCommandLog.remove(account, command)
                logger.debug(TAG) { "Done processing pending command '$commandName'" }
            } catch (e: MessagingException) {
                if (!e.isPermanentFailure) throw e

                logger.error(TAG, e) { "Failure of command '$commandName' was permanent, removing command from queue" }
                pendingCommandLog.remove(account, command)
            } catch (e: Exception) {
                logger.error(TAG, e) { "Unexpected exception with command '$commandName', removing command from queue" }
                pendingCommandLog.remove(account, command)

                if (isDebug) {
                    throw AssertionError("Unexpected exception while processing pending command", e)
                }
            }
        }
    }
}
