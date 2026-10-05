package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * A periodic sync skips a folder the user refreshed less than one check interval ago; the next periodic sync after
 * that interval fetches what arrived meanwhile.
 */
class FolderSyncedTooRecentlyScenarioTest : ScenarioTest() {

    @Test
    fun `periodic sync skips a folder refreshed less than an interval ago`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES)
        // The first periodic sync runs right after setup, at minute 1.
        device.advanceTime(1.minutes)
        device.advanceTime(9.minutes)
        server.deliver(user) { inbox { message(REFRESHED) } }
        driver.pullToRefresh(account, FolderPath.INBOX)
        server.deliver(user) { inbox { message(LATER) } }

        // Act
        // Minute 16: the periodic sync is due, but INBOX was checked at minute 10.
        device.advanceTime(6.minutes)

        // Assert
        assertThat(driver.subjects(account)).containsExactly(REFRESHED)

        // Act
        // Minute 31: the next periodic sync, more than an interval after the refresh.
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)

        // Assert
        assertThat(driver.subjects(account)).containsExactly(LATER, REFRESHED)
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val REFRESHED = "Fetched by pull to refresh"
        const val LATER = "Arrived after the refresh"
    }
}
