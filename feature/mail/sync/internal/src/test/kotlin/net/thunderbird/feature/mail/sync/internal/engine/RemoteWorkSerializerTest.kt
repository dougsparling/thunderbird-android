package net.thunderbird.feature.mail.sync.internal.engine

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.thunderbird.core.logging.testing.TestLogger

class RemoteWorkSerializerTest {
    private val uncaught = AtomicReference<Throwable?>(null)
    private val threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "test-remote-work").apply {
            setUncaughtExceptionHandler { _, throwable -> uncaught.set(throwable) }
        }
    }
    private val testSubject = RemoteWorkSerializer(logger = TestLogger(), threadFactory = threadFactory)
    private val ran = Collections.synchronizedList(mutableListOf<String>())

    @AfterTest
    fun tearDown() {
        testSubject.stop()
    }

    @Test
    fun `work with the same priority runs in the order it was queued`() {
        // Arrange
        val gate = blockSerializer()

        // Act
        testSubject.enqueue("a", WorkPriority.BACKGROUND) { ran.add("a") }
        testSubject.enqueue("b", WorkPriority.BACKGROUND) { ran.add("b") }
        testSubject.enqueue("c", WorkPriority.BACKGROUND) { ran.add("c") }
        gate.countDown()
        awaitIdle()

        // Assert
        assertThat(ran).containsExactly("a", "b", "c")
    }

    @Test
    fun `foreground work runs before background work queued earlier`() {
        // Arrange
        val gate = blockSerializer()

        // Act
        testSubject.enqueue("background 1", WorkPriority.BACKGROUND) { ran.add("background 1") }
        testSubject.enqueue("foreground 1", WorkPriority.FOREGROUND) { ran.add("foreground 1") }
        testSubject.enqueue("background 2", WorkPriority.BACKGROUND) { ran.add("background 2") }
        testSubject.enqueue("foreground 2", WorkPriority.FOREGROUND) { ran.add("foreground 2") }
        gate.countDown()
        awaitIdle()

        // Assert
        assertThat(ran).containsExactly("foreground 1", "foreground 2", "background 1", "background 2")
    }

    @Test
    fun `work queued by running work runs afterwards`() {
        // Act
        testSubject.enqueue("outer", WorkPriority.BACKGROUND) {
            testSubject.enqueue("inner", WorkPriority.FOREGROUND) { ran.add("inner") }
            ran.add("outer")
        }
        awaitIdle()

        // Assert
        assertThat(ran).containsExactly("outer", "inner")
    }

    @Test
    fun `is not idle while work is queued or running`() {
        // Arrange
        val gate = blockSerializer()

        // Act
        testSubject.enqueue("queued", WorkPriority.BACKGROUND) { ran.add("queued") }
        val idleWhileBusy = testSubject.isIdle.value
        gate.countDown()
        awaitIdle()

        // Assert
        assertThat(idleWhileBusy).isFalse()
        assertThat(testSubject.isIdle.value).isTrue()
    }

    @Test
    fun `an exception is logged and the next work still runs`() {
        // Act
        testSubject.enqueue("failing", WorkPriority.BACKGROUND) { error("failed") }
        testSubject.enqueue("next", WorkPriority.BACKGROUND) { ran.add("next") }
        awaitIdle()

        // Assert
        assertThat(ran).containsExactly("next")
        assertThat(testSubject.failure).isNull()
        assertThat(uncaught.get()).isNull()
    }

    @Test
    fun `an error stops the serializer and reaches the thread's uncaught exception handler`() {
        // Arrange
        val error = AssertionError("broken")

        // Act
        testSubject.enqueue("failing", WorkPriority.BACKGROUND) { throw error }
        awaitCondition { uncaught.get() != null }
        testSubject.enqueue("never", WorkPriority.BACKGROUND) { ran.add("never") }
        Thread.sleep(SETTLE_MILLIS)

        // Assert
        assertThat(testSubject.failure).isSameInstanceAs(error)
        assertThat(uncaught.get()).isSameInstanceAs(error)
        assertThat(ran.size).isEqualTo(0)
    }

    /** Occupies the serializer until the returned latch is counted down, so the test can queue work behind it. */
    private fun blockSerializer(): CountDownLatch {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        testSubject.enqueue("gate", WorkPriority.FOREGROUND) {
            started.countDown()
            gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return gate
    }

    private fun awaitIdle() = runBlocking {
        withTimeout(TIMEOUT_SECONDS * 1000) { testSubject.awaitIdle() }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(1)
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        const val SETTLE_MILLIS = 100L
    }
}
