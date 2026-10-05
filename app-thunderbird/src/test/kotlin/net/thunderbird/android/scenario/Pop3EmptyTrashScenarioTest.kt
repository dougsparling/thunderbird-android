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

/** Emptying the trash of a POP3 account empties the app's own Trash and leaves the server alone. */
class Pop3EmptyTrashScenarioTest : ScenarioTest() {

    @Test
    fun `emptying a POP3 account's trash is local`() = scenario {
        // Arrange
        val user = server.user { inbox { message(SUBJECT) } }
        val account = client.account(user, protocol = MailProtocol.POP3)
        driver.delete(account, FolderPath.INBOX, SUBJECT)

        // Act
        driver.emptyTrash(account)

        // Assert
        assertThat(driver.subjects(account, TRASH)).isEmpty()
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)
    }

    private companion object {
        const val SUBJECT = "Thrown away"
    }
}
