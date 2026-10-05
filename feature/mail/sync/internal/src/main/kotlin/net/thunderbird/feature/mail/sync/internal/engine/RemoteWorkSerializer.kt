package net.thunderbird.feature.mail.sync.internal.engine

import java.util.PriorityQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.thunderbird.core.logging.Logger

private const val TAG = "RemoteWorkSerializer"

/** Order in which queued work runs: all foreground work before any background work. */
enum class WorkPriority {
    FOREGROUND,
    BACKGROUND,
}

/**
 * Runs remote work (talking to mail servers, and the local changes that go with it) one piece at a time, for all
 * accounts.
 *
 * Work runs in the order it was queued, except that foreground work (what the user is waiting for, like a message
 * body) runs before background work queued earlier. Work that is running is never interrupted. All work runs on one
 * background thread, so blocking calls are fine.
 *
 * An [Exception] thrown by a piece of work is logged and the next piece runs. Any other [Throwable] (an [Error], e.g.
 * a failed assertion in a debug build) stops the serializer for good: it's recorded in [failure] and passed on to the
 * thread's uncaught exception handler, which crashes the app.
 */
class RemoteWorkSerializer(
    private val logger: Logger,
    threadFactory: ThreadFactory = BackgroundThreadFactory(THREAD_NAME),
) {
    private val lock = Any()
    private val queue = PriorityQueue<QueuedWork>()
    private var running: QueuedWork? = null
    private var nextSequence = 0L
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    private val idleState = MutableStateFlow(true)

    /** `true` while no work is queued or running. */
    val isIdle: StateFlow<Boolean> = idleState.asStateFlow()

    /** What stopped the serializer, if anything did. Work queued afterwards never runs. */
    @Volatile
    var failure: Throwable? = null
        private set

    private val executor = Executors.newSingleThreadExecutor(threadFactory)
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    init {
        scope.launch { runQueuedWork() }
    }

    /** Queues [work]; it runs after all queued work of the same or a higher [priority]. */
    fun enqueue(description: String, priority: WorkPriority, work: suspend () -> Unit) {
        synchronized(lock) {
            queue.add(QueuedWork(description, priority, nextSequence++, work))
            updateIdleState()
        }
        wakeUp.trySend(Unit)
    }

    /** Suspends until no work is queued or running. */
    suspend fun awaitIdle() {
        isIdle.first { it }
    }

    /** Stops running work; queued work is dropped. For tests and app restarts. */
    fun stop() {
        scope.cancel()
        executor.shutdown()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runQueuedWork() {
        while (true) {
            val work = takeNext()
            if (work == null) {
                wakeUp.receive()
                continue
            }

            try {
                logger.info(TAG) { "Running '${work.description}', seq = ${work.sequence} (${work.priority})" }
                work.run()
                logger.info(TAG) { "'${work.description}' completed" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(TAG, e) { "Error running '${work.description}'" }
            } catch (e: Throwable) {
                failure = e
                throw e
            } finally {
                finishRunning()
            }
        }
    }

    private fun takeNext(): QueuedWork? = synchronized(lock) {
        queue.poll().also { work ->
            running = work
            updateIdleState()
        }
    }

    private fun finishRunning() {
        synchronized(lock) {
            running = null
            updateIdleState()
        }
    }

    private fun updateIdleState() {
        idleState.value = running == null && queue.isEmpty()
    }

    private class QueuedWork(
        val description: String,
        val priority: WorkPriority,
        val sequence: Long,
        val run: suspend () -> Unit,
    ) : Comparable<QueuedWork> {
        override fun compareTo(other: QueuedWork): Int {
            return compareValuesBy(this, other, { it.priority }, { it.sequence })
        }
    }

    private companion object {
        const val THREAD_NAME = "RemoteWork"
    }
}

/** Creates threads with background priority, like the thread the removed `MessagingController` used. */
private class BackgroundThreadFactory(private val name: String) : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread {
        return Thread(
            {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            },
            name,
        )
    }
}
