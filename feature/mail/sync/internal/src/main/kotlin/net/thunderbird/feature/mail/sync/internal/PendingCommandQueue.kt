package net.thunderbird.feature.mail.sync.internal

import com.fsck.k9.controller.PendingCommand
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.mail.sync.internal.engine.PendingCommandReplay
import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer
import net.thunderbird.feature.mail.sync.internal.engine.WorkPriority

private const val TAG = "PendingCommandQueue"

/** Records local changes that have to reach the server as pending commands, and sends them. */
internal class PendingCommandQueue(
    private val accounts: AccountStores,
    private val serializer: RemoteWorkSerializer,
    private val replay: PendingCommandReplay,
    private val processor: PendingCommandProcessor,
    private val serverErrorNotifier: ServerErrorNotifier,
    private val logger: Logger,
) {
    /** Adds [command] to the account's pending commands; it's sent the next time they're processed. */
    @Suppress("TooGenericExceptionCaught", "TooGenericExceptionThrown")
    fun add(account: LegacyAccountDto, command: PendingCommand) {
        try {
            accounts.localStore(account).addPendingCommand(command)
        } catch (e: Exception) {
            throw RuntimeException("Unable to enqueue pending command", e)
        }
    }

    /** Queues processing the account's pending commands, as background work. Failures are retried next time. */
    fun processInBackground(account: LegacyAccountDto) {
        serializer.enqueue("processPendingCommands", WorkPriority.BACKGROUND) {
            try {
                processNow(account)
            } catch (e: MessagingException) {
                // Ignore any exceptions from the commands. Commands will be processed on the next round.
                logger.error(TAG, e) { "processPendingCommands" }
            }
        }
    }

    /**
     * Processes the account's pending commands right away, see [PendingCommandReplay].
     *
     * @throws MessagingException if a command failed temporarily; it and later commands stay pending.
     */
    @Throws(MessagingException::class)
    fun processNow(account: LegacyAccountDto) {
        try {
            replay.replay(account, processor)
        } catch (e: MessagingException) {
            serverErrorNotifier.notifyUserIfCertificateProblem(account, e, incoming = true)
            logger.error(TAG, e) { "Could not process pending commands" }
            throw e
        }
    }
}
