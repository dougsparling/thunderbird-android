package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.NotificationButton
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SpecialUse

/** The "Archive" button of a new-mail notification moves the message to the archive folder. */
class NotificationArchiveActionScenarioTest : ScenarioTest() {

    @Test
    fun `archive from the notification`() = scenario {
        // Arrange
        driver.setNotificationActions(listOf(NotificationButton.ARCHIVE))
        val user = server.user {
            folder(ARCHIVE.segments.single(), specialUse = SpecialUse.ARCHIVE)
        }
        client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES, notifyNewMail = true)
        device.advanceTime(1.minutes)
        server.deliver(user) { inbox { message(SUBJECT) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        val notification = device.notifications().single()

        // Act
        device.tapNotificationAction(notification, "Archive")

        // Assert
        eventually {
            val serverState = server.stateOf(user)
            assertThat(serverState.folder(FolderPath.INBOX).messages).isEmpty()
            assertThat(serverState.folder(ARCHIVE).subjects).containsExactly(SUBJECT)
        }
    }

    private companion object {
        val ARCHIVE = FolderPath.of("Archive")
        const val CHECK_INTERVAL_MINUTES = 15
        const val SUBJECT = "Keep for the records"
    }
}
