package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
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
 * With "delete from server: mark as read", deleting a message only marks it read on the server, where it stays in the
 * inbox; the app moves it to its Trash.
 */
class DeletePolicyMarkReadScenarioTest : ScenarioTest() {

    @Test
    fun `deleting marks the message read on the server`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
        }
        val account = client.account(
            user,
            settings = ClientAccountSettings(deleteFromServer = DeleteFromServer.MARK_AS_READ),
        )

        // Act
        driver.delete(account, FolderPath.INBOX, SUBJECT)

        // Assert
        assertThat(driver.subjects(account, FolderPath.INBOX)).isEmpty()
        val serverState = server.stateOf(user)
        val onServer = serverState.folder(FolderPath.INBOX).message(SUBJECT)
        assertThat(onServer.flags).contains(SystemFlag.SEEN)
        assertThat(onServer.flags).doesNotContain(SystemFlag.DELETED)
        assertThat(serverState.folder(TRASH).messages).isEmpty()
    }

    private companion object {
        const val SUBJECT = "Read and forget"
    }
}
