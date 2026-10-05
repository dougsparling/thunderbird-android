package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccountSettings
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/** With "mark as read when deleted" off, a deleted message lands in the server's Trash still unread. */
class DeleteKeepsUnreadScenarioTest : ScenarioTest() {

    @Test
    fun `a deleted message stays unread when mark as read on delete is off`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
        }
        val account = client.account(user, settings = ClientAccountSettings(markReadOnDelete = false))

        // Act
        driver.delete(account, FolderPath.INBOX, SUBJECT)

        // Assert
        val serverState = server.stateOf(user)
        assertThat(serverState.folder(FolderPath.INBOX).subjects).doesNotContain(SUBJECT)
        assertThat(serverState.folder(TRASH).message(SUBJECT).flags).doesNotContain(SystemFlag.SEEN)
    }

    private companion object {
        const val SUBJECT = "Unread in the trash"
    }
}
