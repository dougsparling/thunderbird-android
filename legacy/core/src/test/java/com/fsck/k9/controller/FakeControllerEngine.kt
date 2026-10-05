package com.fsck.k9.controller

import net.thunderbird.core.android.account.LegacyAccountDto

/** Records queued work without running it; tests call the controller's synchronous methods directly. */
class FakeControllerEngine : ControllerEngine {
    val queuedWork = mutableListOf<String>()

    override fun enqueue(description: String, isForeground: Boolean, runnable: Runnable) {
        queuedWork.add(description)
    }

    override fun processPendingCommands(account: LegacyAccountDto, executor: PendingCommandExecutor) = Unit
}
