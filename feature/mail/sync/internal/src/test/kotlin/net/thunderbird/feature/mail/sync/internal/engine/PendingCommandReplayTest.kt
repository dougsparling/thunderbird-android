package net.thunderbird.feature.mail.sync.internal.engine

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import com.fsck.k9.controller.MessagingControllerCommands.PendingCommand
import com.fsck.k9.controller.MessagingControllerCommands.PendingExpunge
import com.fsck.k9.controller.PendingCommandExecutor
import kotlin.test.Test
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.logging.testing.TestLogger

class PendingCommandReplayTest {
    private val account = LegacyAccountDto("00000000-0000-4000-8000-000000000001")
    private val first = PendingExpunge.create(1)
    private val second = PendingExpunge.create(2)
    private val third = PendingExpunge.create(3)
    private val log = FakePendingCommandLog(mutableListOf(first, second, third))
    private val executed = mutableListOf<PendingCommand>()

    @Test
    fun `runs every command oldest first and removes it`() {
        // Arrange
        val testSubject = createTestSubject()

        // Act
        testSubject.replay(account, recordingExecutor())

        // Assert
        assertThat(executed).containsExactly(first, second, third)
        assertThat(log.commands).isEmpty()
    }

    @Test
    fun `a temporary failure stops the replay and keeps that command and later ones`() {
        // Arrange
        val testSubject = createTestSubject()
        val failure = MessagingException("connection lost", false)

        // Act
        val result = assertFailure {
            testSubject.replay(account, recordingExecutor(failOn = second, failure = failure))
        }

        // Assert
        result.isSameInstanceAs(failure)
        assertThat(executed).containsExactly(first, second)
        assertThat(log.commands).containsExactly(second, third)
    }

    @Test
    fun `a permanent failure removes the command and continues`() {
        // Arrange
        val testSubject = createTestSubject()

        // Act
        testSubject.replay(account, recordingExecutor(failOn = second, failure = MessagingException("gone", true)))

        // Assert
        assertThat(executed).containsExactly(first, second, third)
        assertThat(log.commands).isEmpty()
    }

    @Test
    fun `an unexpected exception removes the command and continues in release builds`() {
        // Arrange
        val testSubject = createTestSubject(isDebug = false)

        // Act
        testSubject.replay(account, recordingExecutor(failOn = second, failure = IllegalStateException("bug")))

        // Assert
        assertThat(executed).containsExactly(first, second, third)
        assertThat(log.commands).isEmpty()
    }

    @Test
    fun `an unexpected exception removes the command and fails an assertion in debug builds`() {
        // Arrange
        val testSubject = createTestSubject(isDebug = true)

        // Act
        val result = assertFailure {
            testSubject.replay(account, recordingExecutor(failOn = second, failure = IllegalStateException("bug")))
        }

        // Assert
        result.isInstanceOf<AssertionError>()
        assertThat(executed).containsExactly(first, second)
        assertThat(log.commands).containsExactly(third)
    }

    private fun createTestSubject(isDebug: Boolean = false) = PendingCommandReplay(
        pendingCommandLog = log,
        logger = TestLogger(),
        isDebug = isDebug,
    )

    private fun recordingExecutor(failOn: PendingCommand? = null, failure: Exception? = null) =
        PendingCommandExecutor { command, _ ->
            executed.add(command)
            if (command === failOn) throw checkNotNull(failure)
        }

    private class FakePendingCommandLog(val commands: MutableList<PendingCommand>) : PendingCommandLog {
        override fun getAll(account: LegacyAccountDto): List<PendingCommand> = commands.toList()

        override fun remove(account: LegacyAccountDto, command: PendingCommand) {
            commands.remove(command)
        }
    }
}
