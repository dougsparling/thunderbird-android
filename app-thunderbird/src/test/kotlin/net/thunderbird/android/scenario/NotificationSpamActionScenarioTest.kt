package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.NotificationButton
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/** The "Spam" button of a new-mail notification moves the message to the spam folder. */
class NotificationSpamActionScenarioTest : ScenarioTest() {

    @Test
    fun `spam from the notification`() = scenario {
        // Arrange
        driver.setNotificationActions(listOf(NotificationButton.SPAM))
        val user = server.user()
        client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES, notifyNewMail = true)
        device.advanceTime(1.minutes)
        server.deliver(user) { inbox { message(SUBJECT) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        val notification = device.notifications().single()

        // Act
        device.tapNotificationAction(notification, "Spam")

        // Assert
        eventually {
            val serverState = server.stateOf(user)
            assertThat(serverState.folder(FolderPath.INBOX).messages).isEmpty()
            assertThat(serverState.folder(SPAM).subjects).containsExactly(SUBJECT)
        }
    }

    private companion object {
        val SPAM = FolderPath.of("Spam")
        const val CHECK_INTERVAL_MINUTES = 15
        const val SUBJECT = "Limited offer"
    }
}
