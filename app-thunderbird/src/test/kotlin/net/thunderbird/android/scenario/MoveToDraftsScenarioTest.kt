package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * "Move to Drafts" saves the message as a draft (uploaded to the server's Drafts) and removes it from the app's
 * inbox. Today it only removes the app's copy: the server keeps the original in INBOX, so the next refresh shows it
 * in the inbox again. Pinned as current behaviour; see the scenario backlog.
 */
class MoveToDraftsScenarioTest : ScenarioTest() {

    @Test
    fun `move to drafts saves a draft and removes only the app's copy`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
        }
        val account = client.account(user)

        // Act
        driver.moveToDrafts(account, FolderPath.INBOX, SUBJECT)

        // Assert
        assertThat(driver.subjects(account, FolderPath.INBOX)).isEmpty()
        assertThat(driver.subjects(account, DRAFTS)).containsExactly(SUBJECT)
        assertThat(server.stateOf(user).folder(DRAFTS).subjects).containsExactly(SUBJECT)
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)

        // Act
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.subjects(account, FolderPath.INBOX)).containsExactly(SUBJECT)
    }

    private companion object {
        val DRAFTS = FolderPath.of("Drafts")
        const val SUBJECT = "Template to reuse"
    }
}
