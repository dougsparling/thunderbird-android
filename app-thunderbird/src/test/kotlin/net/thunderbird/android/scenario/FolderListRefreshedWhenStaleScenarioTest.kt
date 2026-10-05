package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ClientFolder
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The app refreshes the folder list as part of syncing once its copy is more than 30 minutes old, so a folder created
 * on another device shows up without the user refreshing the folder list.
 */
class FolderListRefreshedWhenStaleScenarioTest : ScenarioTest() {

    @Test
    fun `a stale folder list is refreshed by periodic sync`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES)
        device.advanceTime(1.minutes)
        server.deliver(user) { folder(NEW_FOLDER.segments.single()) { message("Hello") } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        assertThat(driver.folderList(account).map(ClientFolder::path)).doesNotContain(NEW_FOLDER)

        // Act
        device.advanceTime((2 * CHECK_INTERVAL_MINUTES).minutes)

        // Assert
        assertThat(driver.folderList(account).map(ClientFolder::path)).contains(NEW_FOLDER)
    }

    private companion object {
        val NEW_FOLDER = FolderPath.of("Projects")
        const val CHECK_INTERVAL_MINUTES = 15
    }
}
