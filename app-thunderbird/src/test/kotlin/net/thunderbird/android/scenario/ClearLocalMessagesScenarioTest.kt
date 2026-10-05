package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * "Clear local messages" empties the app's copy of a folder without touching the server; the next refresh downloads
 * the messages again.
 */
class ClearLocalMessagesScenarioTest : ScenarioTest() {

    @Test
    fun `clearing local messages leaves the server alone`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
        }
        val account = client.account(user)

        // Act
        driver.clearLocalMessages(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.subjects(account, FolderPath.INBOX)).isEmpty()
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)

        // Act
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.subjects(account, FolderPath.INBOX)).containsExactly(SUBJECT)
    }

    private companion object {
        const val SUBJECT = "Comes back"
    }
}
