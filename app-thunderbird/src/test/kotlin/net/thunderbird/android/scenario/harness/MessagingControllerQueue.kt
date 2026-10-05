package net.thunderbird.android.scenario.harness

import app.k9mail.legacy.message.controller.MessagingListener
import com.fsck.k9.controller.MessagingController
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Lets the legacy driver wait until [MessagingController] has finished all queued work.
 *
 * Most controller operations (setting flags, sending pending commands to the server, the mail check after account
 * setup) are queued on the controller's single background thread and may queue follow-up commands. There is no public
 * way to wait for them, so this reaches into the controller by reflection. That couples the harness to legacy
 * internals on purpose: it lives only in the legacy driver, which is replaced together with the legacy sync core.
 *
 * The controller thread only catches `Exception`s. An `Error` ends it for good; in debug builds that happens whenever
 * a pending command fails unexpectedly (`MessagingController.processPendingCommandsSynchronous` throws
 * `AssertionError`). Nothing would run queued work after that, so waits fail right away with the error that ended the
 * thread instead of timing out.
 */
internal class MessagingControllerQueue(private val controller: MessagingController) {
    private val putBackground = MessagingController::class.java
        .getDeclaredMethod("putBackground", String::class.java, MessagingListener::class.java, Runnable::class.java)
        .apply { isAccessible = true }

    private val queuedCommands: BlockingQueue<*> = MessagingController::class.java
        .getDeclaredField("queuedCommands")
        .apply { isAccessible = true }
        .get(controller) as BlockingQueue<*>

    private val controllerThread: Thread = MessagingController::class.java
        .getDeclaredField("controllerThread")
        .apply { isAccessible = true }
        .get(controller) as Thread

    private val stop = MessagingController::class.java
        .getDeclaredMethod("stop")
        .apply { isAccessible = true }

    /** What ended the controller thread, if it died of an uncaught exception or error. */
    private val threadFailure = AtomicReference<Throwable?>(null)

    init {
        val previousHandler = controllerThread.uncaughtExceptionHandler
        controllerThread.setUncaughtExceptionHandler { thread, throwable ->
            threadFailure.set(throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Returns once the controller thread has run every queued command, including commands queued by those commands.
     *
     * Enqueues a marker command at background priority, behind everything already queued. When the marker runs and
     * the queue is empty, the controller is idle; otherwise work was queued meanwhile and we try again.
     *
     * Fails with [ControllerThreadDiedException] if the controller thread has died, before or while waiting.
     */
    fun awaitIdle(pump: MainLooperPump) {
        do {
            checkControllerAlive()
            val done = CountDownLatch(1)
            val queueWasEmpty = AtomicBoolean(false)
            invoke {
                putBackground.invoke(
                    controller,
                    "scenario: await idle",
                    null,
                    Runnable {
                        queueWasEmpty.set(queuedCommands.isEmpty())
                        done.countDown()
                    },
                )
            }
            pump.awaitCondition("MessagingController to finish queued commands") {
                done.count == 0L || !controllerThread.isAlive
            }
            if (done.count != 0L) checkControllerAlive()
        } while (!queueWasEmpty.get())
    }

    /** Stops the controller thread, which otherwise outlives the test. */
    fun stopController() {
        invoke { stop.invoke(controller) }
    }

    private fun checkControllerAlive() {
        if (controllerThread.isAlive) return

        val cause = threadFailure.get()
        val reason = if (cause == null) {
            "without an uncaught exception"
        } else {
            "of ${cause.javaClass.name}: ${cause.message}"
        }
        throw ControllerThreadDiedException(
            "The MessagingController thread died $reason. Nothing will run the app's queued work any more, so the " +
                "scenario can't continue. In debug builds, pending commands that fail unexpectedly throw " +
                "AssertionError (MessagingController.processPendingCommandsSynchronous), which ends this thread.",
            cause,
        )
    }

    private fun invoke(block: () -> Unit) {
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}

/** The app's `MessagingController` thread is gone, see [MessagingControllerQueue]. [cause] is what ended it. */
class ControllerThreadDiedException(message: String, cause: Throwable?) : IllegalStateException(message, cause)
