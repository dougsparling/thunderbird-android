package net.thunderbird.android.scenario.harness

import net.thunderbird.feature.mail.sync.internal.engine.RemoteWorkSerializer

/**
 * Lets the driver wait until the app has finished its queued remote work.
 *
 * Most operations (setting flags, sending pending commands to the server, the mail check after account setup) run on
 * the app's [RemoteWorkSerializer], one at a time, and may queue follow-up work. Waiting for the serializer to be idle
 * covers that follow-up work too.
 *
 * The serializer stops for good when a piece of work throws an `Error`; in debug builds that happens whenever a
 * pending command fails unexpectedly (see `PendingCommandReplay`). Nothing would run queued work after that, so waits
 * fail right away with the error that stopped it instead of timing out.
 */
internal class RemoteWorkQueue(private val serializer: RemoteWorkSerializer) {
    /** Returns once no remote work is queued or running. Fails with [SyncEngineStoppedException] if it never will. */
    fun awaitIdle(pump: MainLooperPump) {
        checkRunning()
        pump.awaitCondition("the app to finish its queued remote work") {
            serializer.isIdle.value || serializer.failure != null
        }
        checkRunning()
    }

    /** Stops the serializer's thread, which otherwise outlives the test. */
    fun stop() {
        serializer.stop()
    }

    private fun checkRunning() {
        val cause = serializer.failure ?: return
        throw SyncEngineStoppedException(
            "The app's remote work queue stopped because of ${cause.javaClass.name}: ${cause.message}. Nothing will " +
                "run the app's queued work any more, so the scenario can't continue. In debug builds, pending " +
                "commands that fail unexpectedly throw AssertionError (PendingCommandReplay).",
            cause,
        )
    }
}

/** The app's [RemoteWorkSerializer] stopped, see [RemoteWorkQueue]. [cause] is what stopped it. */
class SyncEngineStoppedException(message: String, cause: Throwable?) : IllegalStateException(message, cause)
