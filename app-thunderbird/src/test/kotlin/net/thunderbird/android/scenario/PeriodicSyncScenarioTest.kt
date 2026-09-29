package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects

/**
 * With a 15-minute check interval, mail that arrives between two periodic syncs shows up with the next one, not
 * before.
 */
class PeriodicSyncScenarioTest : ScenarioTest() {

    @Test
    fun `periodic sync fetches new mail once the check interval has passed`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(FIRST_SUBJECT) }
        }
        val account = client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES)
        // The first periodic sync is due right after setup.
        device.advanceTime(1.minutes)
        assertThat(driver.subjects(account)).containsExactly(FIRST_SUBJECT)
        server.deliver(user) {
            inbox { message(SECOND_SUBJECT) }
        }

        // Act
        device.advanceTime(5.minutes)

        // Assert
        assertThat(driver.subjects(account)).containsExactly(FIRST_SUBJECT)

        // Act
        device.advanceTime(10.minutes)

        // Assert
        assertThat(driver.subjects(account)).containsExactly(SECOND_SUBJECT, FIRST_SUBJECT)
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val FIRST_SUBJECT = "Already there"
        const val SECOND_SUBJECT = "Just arrived"
    }
}
