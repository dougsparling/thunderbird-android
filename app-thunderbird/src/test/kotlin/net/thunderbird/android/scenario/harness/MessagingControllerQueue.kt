package net.thunderbird.android.scenario.harness

import app.k9mail.legacy.message.controller.MessagingListener
import com.fsck.k9.controller.MessagingController
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets the legacy driver wait until [MessagingController] has finished all queued work.
 *
 * Most controller operations (setting flags, sending pending commands to the server, the mail check after account
 * setup) are queued on the controller's single background thread and may queue follow-up commands. There is no public
 * way to wait for them, so this reaches into the controller by reflection. That couples the harness to legacy
 * internals on purpose: it lives only in the legacy driver, which is replaced together with the legacy sync core.
 */
internal class MessagingControllerQueue(private val controller: MessagingController) {
    private val putBackground = MessagingController::class.java
        .getDeclaredMethod("putBackground", String::class.java, MessagingListener::class.java, Runnable::class.java)
        .apply { isAccessible = true }

    private val queuedCommands: BlockingQueue<*> = MessagingController::class.java
        .getDeclaredField("queuedCommands")
        .apply { isAccessible = true }
        .get(controller) as BlockingQueue<*>

    private val stop = MessagingController::class.java
        .getDeclaredMethod("stop")
        .apply { isAccessible = true }

    /**
     * Returns once the controller thread has run every queued command, including commands queued by those commands.
     *
     * Enqueues a marker command at background priority, behind everything already queued. When the marker runs and
     * the queue is empty, the controller is idle; otherwise work was queued meanwhile and we try again.
     */
    fun awaitIdle(pump: MainLooperPump) {
        do {
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
            pump.awaitCondition("MessagingController to finish queued commands") { done.count == 0L }
        } while (!queueWasEmpty.get())
    }

    /** Stops the controller thread, which otherwise outlives the test. */
    fun stopController() {
        invoke { stop.invoke(controller) }
    }

    private fun invoke(block: () -> Unit) {
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
