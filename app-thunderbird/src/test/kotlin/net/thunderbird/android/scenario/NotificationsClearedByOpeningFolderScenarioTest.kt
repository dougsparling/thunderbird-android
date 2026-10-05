package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.prop
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.message
import net.thunderbird.mail.testserver.fixture.FolderPath

/** Opening the inbox dismisses its new-mail notifications; the messages stay unread. */
class NotificationsClearedByOpeningFolderScenarioTest : ScenarioTest() {

    @Test
    fun `opening the inbox dismisses its notifications`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES, notifyNewMail = true)
        device.advanceTime(1.minutes)
        server.deliver(user) { inbox { message(SUBJECT) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        assertThat(device.notifications()).hasSize(1)

        // Act
        driver.openFolder(account, FolderPath.INBOX)

        // Assert
        eventually { assertThat(device.notifications()).isEmpty() }
        assertThat(driver.message(account, SUBJECT)).prop(ClientMessage::isRead).isFalse()
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val SUBJECT = "Seen in the list"
    }
}
