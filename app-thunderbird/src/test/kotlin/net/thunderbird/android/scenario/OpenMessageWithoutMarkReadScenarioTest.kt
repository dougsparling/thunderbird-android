package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.prop
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ClientAccountSettings
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.message
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * With "mark as read when opened" off, opening a message leaves it unread in the app and on the server, but still
 * removes its new-mail notification.
 */
class OpenMessageWithoutMarkReadScenarioTest : ScenarioTest() {

    @Test
    fun `opening keeps the message unread but removes its notification`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(
            user,
            checkIntervalMinutes = CHECK_INTERVAL_MINUTES,
            notifyNewMail = true,
            settings = ClientAccountSettings(markReadOnOpen = false),
        )
        device.advanceTime(1.minutes)
        server.deliver(user) { inbox { message(SUBJECT) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        assertThat(device.notifications()).hasSize(1)

        // Act
        driver.open(account, FolderPath.INBOX, SUBJECT)

        // Assert
        assertThat(driver.message(account, SUBJECT)).prop(ClientMessage::isRead).isFalse()
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags).doesNotContain(SystemFlag.SEEN)
        // The app removes the notification in the background.
        eventually { assertThat(device.notifications()).isEmpty() }
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val SUBJECT = "Read me later"
    }
}
