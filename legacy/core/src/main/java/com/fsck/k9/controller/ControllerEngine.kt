package com.fsck.k9.controller

import com.fsck.k9.controller.PendingCommand
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException

/**
 * Runs [MessagingController]'s work on the sync engine.
 *
 * Temporary seam: the engine lives in `:feature:mail:sync:internal`, which depends on this module, so the controller
 * reaches it through this interface. It goes away together with the controller.
 */
interface ControllerEngine {
    /**
     * Queues [runnable] to run after all queued work of the same or a higher priority; foreground work runs before
     * background work. Work for all accounts shares one queue and runs one piece at a time.
     */
    fun enqueue(description: String, isForeground: Boolean, runnable: Runnable)

    /**
     * Runs the account's pending commands, oldest first, removing each one that succeeds or fails permanently.
     *
     * Stops at the first command that fails temporarily and throws its [MessagingException]; that command and the
     * ones after it stay pending, so they're retried in order next time.
     */
    @Throws(MessagingException::class)
    fun processPendingCommands(account: LegacyAccountDto, executor: PendingCommandExecutor)
}

/** Carries out one pending command against the server. */
fun interface PendingCommandExecutor {
    @Throws(MessagingException::class)
    fun execute(command: PendingCommand, account: LegacyAccountDto)
}
