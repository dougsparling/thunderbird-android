package net.thunderbird.feature.mail.sync.internal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.sync.api.SyncEvent

/**
 * Delivers [SyncEvent]s to everyone observing them, and remembers the state of each folder's sync, so a new observer
 * learns about syncs that already finished, failed or are running.
 */
internal class SyncEventBus {
    private val lock = Any()
    private val subscribers = mutableSetOf<SendChannel<SyncEvent>>()
    private val folderStates = LinkedHashMap<FolderKey, FolderSyncState>()

    fun emit(event: SyncEvent) {
        synchronized(lock) {
            remember(event)
            for (subscriber in subscribers) {
                subscriber.trySend(event)
            }
        }
    }

    /**
     * Events from now on, after the current state of folder syncs: the outcome of each folder's last sync, then the
     * progress of a running sync, if any.
     */
    fun observe(): Flow<SyncEvent> = callbackFlow {
        synchronized(lock) {
            replayTo(channel)
            subscribers.add(channel)
        }
        awaitClose {
            synchronized(lock) { subscribers.remove(channel) }
        }
    }.buffer(Channel.UNLIMITED)

    /** Forgets the state of the account's folder syncs. */
    fun forgetAccount(accountId: AccountId) {
        synchronized(lock) {
            folderStates.keys.removeAll { it.accountId == accountId }
        }
    }

    private fun remember(event: SyncEvent) {
        when (event) {
            is SyncEvent.FolderSyncStarted -> {
                stateOf(event.accountId, event.folderId).apply {
                    status = SyncStatus.STARTED
                    completed = 0
                    total = 0
                }
            }

            is SyncEvent.FolderSyncFinished -> {
                stateOf(event.accountId, event.folderId).status = SyncStatus.FINISHED
            }

            is SyncEvent.FolderSyncFailed -> {
                stateOf(event.accountId, event.folderId).apply {
                    status = SyncStatus.FAILED
                    failureMessage = event.message
                }
            }

            is SyncEvent.FolderSyncProgress -> {
                stateOf(event.accountId, event.folderId).apply {
                    completed = event.completed
                    total = event.total
                }
            }

            else -> Unit
        }
    }

    private fun replayTo(channel: SendChannel<SyncEvent>) {
        var running: Map.Entry<FolderKey, FolderSyncState>? = null
        for (entry in folderStates.entries) {
            val (key, state) = entry
            when (state.status) {
                SyncStatus.STARTED -> running = entry

                SyncStatus.FINISHED -> channel.trySend(SyncEvent.FolderSyncFinished(key.accountId, key.folderId))

                SyncStatus.FAILED -> {
                    channel.trySend(SyncEvent.FolderSyncFailed(key.accountId, key.folderId, state.failureMessage))
                }

                null -> Unit
            }
        }

        if (running != null) {
            val (key, state) = running
            channel.trySend(SyncEvent.FolderSyncStarted(key.accountId, key.folderId))
            if (state.total > 0) {
                channel.trySend(SyncEvent.FolderSyncProgress(key.accountId, key.folderId, state.completed, state.total))
            }
        }
    }

    private fun stateOf(accountId: AccountId, folderId: Long): FolderSyncState {
        return folderStates.getOrPut(FolderKey(accountId, folderId)) { FolderSyncState() }
    }

    private data class FolderKey(val accountId: AccountId, val folderId: Long)

    private enum class SyncStatus { STARTED, FINISHED, FAILED }

    private class FolderSyncState {
        var status: SyncStatus? = null
        var failureMessage: String = ""
        var completed = 0
        var total = 0
    }
}
