package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.MailProtocol
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Deleting in a POP3 account moves the message to the app's own Trash. With the default for POP3 accounts the server
 * keeps the message, and refreshing doesn't bring it back.
 */
class Pop3DeleteScenarioTest : ScenarioTest() {

    @Test
    fun `deleting in a POP3 account only affects the app`() = scenario {
        // Arrange
        val user = server.user { inbox { message(SUBJECT) } }
        val account = client.account(user, protocol = MailProtocol.POP3)

        // Act
        driver.delete(account, FolderPath.INBOX, SUBJECT)
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.subjects(account)).isEmpty()
        assertThat(driver.subjects(account, TRASH)).containsExactly(SUBJECT)
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)
    }

    private companion object {
        const val SUBJECT = "Downloaded once"
    }
}
