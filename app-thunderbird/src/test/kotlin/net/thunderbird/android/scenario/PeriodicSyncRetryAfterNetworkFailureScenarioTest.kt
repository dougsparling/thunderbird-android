package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects

/**
 * Periodic sync retries after the server couldn't be reached. INBOX has a 15-minute check interval and has been synced
 * once. The next periodic run finds the device online but the server unreachable, and fails; a new message arrives;
 * the server becomes reachable again. The retry, due one backoff delay (5 minutes) after the failure, must fetch the
 * new message. A failed check must not be recorded as though INBOX had been checked: the retry would then skip INBOX
 * as "checked too recently" and the new mail would wait for the next interval.
 *
 * The device stays online throughout. While it's offline Android doesn't run the sync at all, see
 * [PeriodicSyncWaitsForNetworkScenarioTest].
 */
class PeriodicSyncRetryAfterNetworkFailureScenarioTest : ScenarioTest() {

    @Test
    fun `periodic sync retries and fetches mail after the server was unreachable`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(FIRST_SUBJECT) }
        }
        val account = client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES)
        // The first periodic sync is due right after setup.
        device.advanceTime(1.minutes)
        assertThat(driver.subjects(account)).containsExactly(FIRST_SUBJECT)
        // The server can't be reached, though the device is online: new connections are refused and open ones dropped.
        network { refuseConnections() }
        proxy.disconnectAll()
        // The next periodic run is due and fails.
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        assertThat(proxy.transcript()).contains("refuse (rule: refuseConnections)")
        server.deliver(user) {
            inbox { message(SECOND_SUBJECT) }
        }

        // Act
        network { }
        // Only the retry is due in the next backoff delay; the next regular run is 10 minutes after that.
        device.advanceTime(BACKOFF_DELAY_MINUTES.minutes)

        // Assert
        assertThat(driver.subjects(account)).containsExactly(SECOND_SUBJECT, FIRST_SUBJECT)
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val BACKOFF_DELAY_MINUTES = 5
        const val FIRST_SUBJECT = "Already there"
        const val SECOND_SUBJECT = "Arrived while unreachable"
    }
}
