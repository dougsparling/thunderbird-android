package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccountSettings
import net.thunderbird.android.scenario.harness.DeleteFromServer
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * With "delete from server: never", deleting a message moves it to the app's Trash only; the server keeps it in the
 * inbox, untouched.
 */
class DeletePolicyNeverScenarioTest : ScenarioTest() {

    @Test
    fun `deleting never touches the server`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
        }
        val account = client.account(
            user,
            settings = ClientAccountSettings(deleteFromServer = DeleteFromServer.NEVER),
        )

        // Act
        driver.delete(account, FolderPath.INBOX, SUBJECT)

        // Assert
        assertThat(driver.subjects(account, FolderPath.INBOX)).isEmpty()
        assertThat(driver.subjects(account, TRASH)).containsExactly(SUBJECT)
        val serverState = server.stateOf(user)
        assertThat(serverState.folder(FolderPath.INBOX).message(SUBJECT).flags).doesNotContain(SystemFlag.DELETED)
        assertThat(serverState.folder(FolderPath.INBOX).message(SUBJECT).flags).doesNotContain(SystemFlag.SEEN)
        assertThat(serverState.folder(TRASH).messages).isEmpty()
    }

    private companion object {
        const val SUBJECT = "Hide it locally"
    }
}
