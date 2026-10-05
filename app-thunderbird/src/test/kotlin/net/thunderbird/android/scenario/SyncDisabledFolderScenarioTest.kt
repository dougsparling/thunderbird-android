package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Periodic sync skips a folder whose sync the user turned off, but pulling to refresh in that folder still syncs it.
 */
class SyncDisabledFolderScenarioTest : ScenarioTest() {

    @Test
    fun `a folder with sync off only syncs on pull to refresh`() = scenario {
        // Arrange
        val user = server.user { folder(WORK.segments.single()) }
        val account = client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES)
        device.advanceTime(1.minutes)
        driver.setFolderSyncEnabled(account, WORK, enabled = true)
        server.deliver(user) { folder(WORK.segments.single()) { message(BEFORE) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        assertThat(driver.subjects(account, WORK)).containsExactly(BEFORE)

        // Act
        driver.setFolderSyncEnabled(account, WORK, enabled = false)
        server.deliver(user) { folder(WORK.segments.single()) { message(AFTER) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)

        // Assert
        assertThat(driver.subjects(account, WORK)).containsExactly(BEFORE)

        // Act
        driver.pullToRefresh(account, WORK)

        // Assert
        assertThat(driver.subjects(account, WORK)).containsExactly(AFTER, BEFORE)
    }

    private companion object {
        val WORK = FolderPath.of("Work")
        const val CHECK_INTERVAL_MINUTES = 15
        const val BEFORE = "Synced in the background"
        const val AFTER = "Needs a pull to refresh"
    }
}
