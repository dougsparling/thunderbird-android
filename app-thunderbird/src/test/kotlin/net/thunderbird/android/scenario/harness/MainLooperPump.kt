package net.thunderbird.android.scenario.harness

import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.TimeSource
import org.robolectric.Shadows.shadowOf

/**
 * Waits for background work without starving the main thread.
 *
 * Robolectric runs the test on the main thread with a paused main looper: anything the app posts to the main thread
 * only runs when the test idles the looper. If the test simply blocked while app code waited for the main thread, it
 * would deadlock. So every wait here keeps running the main looper, which also delivers the UI-side callbacks the app
 * would see on a device.
 */
internal class MainLooperPump(private val timeout: Duration) : AutoCloseable {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scenario-driver").apply { isDaemon = true }
    }

    /** Runs [block] on a worker thread and returns its result, running the main looper while waiting. */
    fun <T> runInBackground(description: String, block: () -> T): T {
        val future = worker.submit(Callable { block() })
        awaitCondition(description) { future.isDone }
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Runs the main looper until [condition] is true; fails after [timeout]. */
    fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (!condition()) {
            check(deadline.hasNotPassedNow()) { "Timed out after $timeout waiting for: $description" }
            idleMainLooper()
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        idleMainLooper()
    }

    fun idleMainLooper() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    override fun close() {
        worker.shutdownNow()
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 10L
    }
}
