package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Moving a message into a folder must put it there and take it out of where it was: after the move and a refresh the
 * server has the message in Work and not in INBOX, and the app shows the same.
 */
class MoveToFolderScenarioTest : ScenarioTest() {

    @Test
    fun `moving a message into a folder puts it there and removes it from the inbox`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
            folder("Work")
        }
        val account = client.account(user)

        // Act
        driver.move(account, FolderPath.INBOX, SUBJECT, WORK)
        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.pullToRefresh(account, WORK)

        // Assert
        val state = server.stateOf(user)
        assertThat(state.folder(FolderPath.INBOX).subjects).isEmpty()
        assertThat(state.folder(WORK).subjects).containsExactly(SUBJECT)
        assertThat(driver.subjects(account)).isEmpty()
        assertThat(driver.subjects(account, WORK)).containsExactly(SUBJECT)
    }

    private companion object {
        const val SUBJECT = "Project plan"
        val WORK = FolderPath.of("Work")
    }
}
