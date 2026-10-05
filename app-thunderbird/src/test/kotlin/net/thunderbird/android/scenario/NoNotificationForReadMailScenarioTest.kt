package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ClientNotification
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.SystemFlag

/** New mail that's already read when the app finds it (read on another device) doesn't notify. */
class NoNotificationForReadMailScenarioTest : ScenarioTest() {

    @Test
    fun `already read mail doesn't notify`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES, notifyNewMail = true)
        device.advanceTime(1.minutes)
        server.deliver(user) {
            inbox {
                message(SUBJECT) { flags(SystemFlag.SEEN) }
                message(UNREAD)
            }
        }

        // Act
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)

        // Assert
        assertThat(driver.subjects(account)).contains(SUBJECT)
        // Only the unread message notifies, which also shows that this sync does notify.
        assertThat(device.notifications().map(ClientNotification::text)).containsExactly(UNREAD)
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val SUBJECT = "Already seen"
        const val UNREAD = "Not seen yet"
    }
}
