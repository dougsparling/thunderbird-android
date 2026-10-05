package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * With delete confirmation for notifications off, the "Delete" button of a new-mail notification deletes the message
 * (to Trash on the server) and dismisses the notification.
 */
class NotificationDeleteActionScenarioTest : ScenarioTest() {

    @Test
    fun `delete from the notification`() = scenario {
        // Arrange
        driver.setConfirmDeleteFromNotification(false)
        val user = server.user()
        client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES, notifyNewMail = true)
        device.advanceTime(1.minutes)
        server.deliver(user) { inbox { message(SUBJECT) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        val notification = device.notifications().single()

        // Act
        device.tapNotificationAction(notification, "Delete")

        // Assert
        eventually {
            val serverState = server.stateOf(user)
            assertThat(serverState.folder(FolderPath.INBOX).messages).isEmpty()
            assertThat(serverState.folder(TRASH).subjects).containsExactly(SUBJECT)
        }
        eventually { assertThat(device.notifications()).isEmpty() }
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val SUBJECT = "Not interested"
    }
}
