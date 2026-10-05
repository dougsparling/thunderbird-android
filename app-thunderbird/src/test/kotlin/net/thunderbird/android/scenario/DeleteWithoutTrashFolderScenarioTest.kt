package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * An account without a trash folder deletes messages for good: the server removes the message (expunged right away,
 * the default) and the app no longer shows it.
 */
class DeleteWithoutTrashFolderScenarioTest : ScenarioTest() {

    @Test
    fun `without a trash folder deleting removes the message for good`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(SUBJECT)
                message(KEPT)
            }
        }
        server.deleteFolder(user, TRASH)
        val account = client.account(user)

        // Act
        driver.delete(account, FolderPath.INBOX, SUBJECT)

        // Assert
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).subjects).doesNotContain(SUBJECT)
        assertThat(driver.subjects(account, FolderPath.INBOX)).doesNotContain(SUBJECT)
        assertThat(server.stateOf(user).folders.map { it.path }).doesNotContain(TRASH)
        assertThat(driver.folderList(account).filter { it.path == TRASH && !it.isLocalOnly }).isEmpty()
    }

    private companion object {
        const val SUBJECT = "Gone for good"
        const val KEPT = "Still here"
    }
}
