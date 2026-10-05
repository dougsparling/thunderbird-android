package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.NotificationButton
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/** The "Star" button of a new-mail notification stars the message on the server. */
class NotificationStarActionScenarioTest : ScenarioTest() {

    @Test
    fun `star from the notification`() = scenario {
        // Arrange
        driver.setNotificationActions(listOf(NotificationButton.STAR))
        val user = server.user()
        client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES, notifyNewMail = true)
        device.advanceTime(1.minutes)
        server.deliver(user) { inbox { message(SUBJECT) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        val notification = device.notifications().single()

        // Act
        device.tapNotificationAction(notification, "Star")

        // Assert
        eventually {
            val flags = server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags
            assertThat(flags).contains(SystemFlag.FLAGGED)
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val SUBJECT = "Important"
    }
}
