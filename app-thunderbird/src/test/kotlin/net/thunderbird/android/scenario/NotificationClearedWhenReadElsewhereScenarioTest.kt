package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/** When the user reads a message on another device, the next sync removes its new-mail notification. */
class NotificationClearedWhenReadElsewhereScenarioTest : ScenarioTest() {

    @Test
    fun `reading elsewhere clears the notification`() = scenario {
        // Arrange
        val user = server.user()
        client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES, notifyNewMail = true)
        device.advanceTime(1.minutes)
        server.deliver(user) { inbox { message(SUBJECT) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        assertThat(device.notifications()).hasSize(1)
        server.setFlags(user, FolderPath.INBOX, SUBJECT, add = setOf(SystemFlag.SEEN))

        // Act
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)

        // Assert
        assertThat(device.notifications()).isEmpty()
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val SUBJECT = "Read on the laptop"
    }
}
