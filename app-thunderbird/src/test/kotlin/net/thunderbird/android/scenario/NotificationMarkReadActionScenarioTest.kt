package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/** The "Mark Read" button of a new-mail notification marks the message read on the server and dismisses it. */
class NotificationMarkReadActionScenarioTest : ScenarioTest() {

    @Test
    fun `mark read from the notification`() = scenario {
        // Arrange
        val user = server.user()
        client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES, notifyNewMail = true)
        device.advanceTime(1.minutes)
        server.deliver(user) { inbox { message(SUBJECT) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        val notification = device.notifications().single()

        // Act
        device.tapNotificationAction(notification, "Mark Read")

        // Assert
        eventually {
            assertThat(server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags).contains(SystemFlag.SEEN)
        }
        eventually { assertThat(device.notifications()).isEmpty() }
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val SUBJECT = "Quick look"
    }
}
