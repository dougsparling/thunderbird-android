package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ClientNotification
import net.thunderbird.android.scenario.harness.ScenarioTest

/** Several new messages found by one sync each get a notification (grouped under a summary). */
class NotificationsForSeveralMessagesScenarioTest : ScenarioTest() {

    @Test
    fun `each new message gets a notification`() = scenario {
        // Arrange
        val user = server.user()
        client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES, notifyNewMail = true)
        device.advanceTime(1.minutes)
        server.deliver(user) { inbox { SUBJECTS.forEach { message(it) } } }

        // Act
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)

        // Assert
        assertThat(device.notifications().map(ClientNotification::text))
            .containsExactlyInAnyOrder(*SUBJECTS.toTypedArray())
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        val SUBJECTS = listOf("First", "Second", "Third")
    }
}
