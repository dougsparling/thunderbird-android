package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The display count (25 by default) limits how much of a folder the app shows after a pull to refresh: INBOX has 40
 * messages with increasing dates, so the app lists exactly the newest 25 and none of the oldest 15. The cap is about
 * what the user is shown, independent of the sync core, so a rewrite must keep it.
 */
class VisibleLimitScenarioTest : ScenarioTest() {

    @Test
    fun `pull to refresh shows only the newest messages up to the display count`() = scenario {
        // Arrange
        val user = server.user { inbox() }
        val account = client.account(user)
        // Message N gets the fixture's default date for message N, so message 40 is the newest.
        server.deliver(user) {
            inbox { (1..MESSAGE_COUNT).forEach { message("Message $it") } }
        }

        // Act
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        // The app sorts by date, newest first, so these are messages 40 down to 16.
        val newest = (MESSAGE_COUNT downTo MESSAGE_COUNT - DISPLAY_COUNT + 1).map { "Message $it" }
        assertThat(driver.subjects(account)).containsExactly(*newest.toTypedArray())
    }

    private companion object {
        const val DISPLAY_COUNT = 25
        const val MESSAGE_COUNT = 40
    }
}
