package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects

/**
 * Periodic sync while the device is offline. INBOX has a 15-minute check interval and has been synced once. The device
 * goes offline before the next periodic run is due, and a new message arrives. The app must not try to reach the
 * server while offline, and the overdue sync must run as soon as the device is back online, without waiting for the
 * next interval.
 */
class PeriodicSyncWaitsForNetworkScenarioTest : ScenarioTest() {

    @Test
    fun `periodic sync that came due while offline runs when the device is back online`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(FIRST_SUBJECT) }
        }
        val account = client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES)
        // The first periodic sync is due right after setup.
        device.advanceTime(1.minutes)
        assertThat(driver.subjects(account)).containsExactly(FIRST_SUBJECT)

        // Act
        goOffline()
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        server.deliver(user) {
            inbox { message(SECOND_SUBJECT) }
        }

        // Assert
        // The app waited for the network instead of trying the server.
        assertThat(proxy.transcript()).doesNotContain("refuse (rule: refuseConnections)")

        // Act
        // Back online, the overdue sync runs right away; no time passes.
        goOnline()

        // Assert
        assertThat(driver.subjects(account)).containsExactly(SECOND_SUBJECT, FIRST_SUBJECT)
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val FIRST_SUBJECT = "Already there"
        const val SECOND_SUBJECT = "Arrived while offline"
    }
}
