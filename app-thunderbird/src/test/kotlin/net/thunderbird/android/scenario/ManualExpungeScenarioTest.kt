package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccountSettings
import net.thunderbird.android.scenario.harness.ExpungeMode
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * With "erase deleted messages: manually", syncing leaves messages flagged as deleted on the server (the app hides
 * them) until the user expunges the folder. (The app's own deletes expunge right away regardless of this setting.)
 */
class ManualExpungeScenarioTest : ScenarioTest() {

    @Test
    fun `messages flagged deleted stay on the server until the user expunges`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(SUBJECT)
                message(KEPT)
            }
        }
        val account = client.account(user, settings = ClientAccountSettings(expunge = ExpungeMode.MANUALLY))
        server.deleteMessage(user, FolderPath.INBOX, SUBJECT, expunge = false)

        // Act
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        val flagged = server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT)
        assertThat(flagged.flags).contains(SystemFlag.DELETED)
        assertThat(driver.subjects(account, FolderPath.INBOX)).containsExactly(KEPT)

        // Act
        driver.expunge(account, FolderPath.INBOX)

        // Assert
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).subjects).doesNotContain(SUBJECT)
        assertThat(driver.subjects(account, FolderPath.INBOX)).containsExactly(KEPT)
    }

    private companion object {
        const val SUBJECT = "Flagged for deletion elsewhere"
        const val KEPT = "Still here"
    }
}
