package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ClientFolder
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/** Periodic sync syncs a folder that's enabled for sync, but not once the user hides it. */
class HiddenFolderNotSyncedScenarioTest : ScenarioTest() {

    @Test
    fun `periodic sync skips hidden folders`() = scenario {
        // Arrange
        val user = server.user { folder(WORK.segments.single()) }
        val account = client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES)
        device.advanceTime(1.minutes)
        driver.setFolderSyncEnabled(account, WORK, enabled = true)
        server.deliver(user) { folder(WORK.segments.single()) { message(BEFORE) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        assertThat(driver.subjects(account, WORK)).containsExactly(BEFORE)

        // Act
        driver.setFolderVisible(account, WORK, visible = false)
        server.deliver(user) { folder(WORK.segments.single()) { message(AFTER) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)

        // Assert
        assertThat(driver.folderList(account).map(ClientFolder::path)).doesNotContain(WORK)
        assertThat(driver.subjects(account, WORK)).containsExactly(BEFORE)
    }

    private companion object {
        val WORK = FolderPath.of("Work")
        const val CHECK_INTERVAL_MINUTES = 15
        const val BEFORE = "Synced while visible"
        const val AFTER = "Arrived while hidden"
    }
}
