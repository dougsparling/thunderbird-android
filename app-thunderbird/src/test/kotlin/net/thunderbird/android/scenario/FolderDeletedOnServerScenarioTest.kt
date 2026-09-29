package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Another client deletes the synced folder Work, which held message B, while the inbox holds message A. When the user
 * refreshes the folder list and then pulls to refresh the inbox, the app must keep working (no dead sync thread), the
 * folder list must have lost Work and still have INBOX, and the inbox must still show A.
 */
class FolderDeletedOnServerScenarioTest : ScenarioTest() {

    @Test
    fun `a folder deleted elsewhere disappears from the list without breaking the inbox`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT_A) }
            folder("Work") { message(SUBJECT_B) }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, WORK)

        // Act
        server.deleteFolder(user, WORK)
        driver.refreshFolders(account)
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        val folderPaths = driver.folderList(account).map { it.path }
        assertThat(folderPaths).doesNotContain(WORK)
        assertThat(folderPaths).contains(FolderPath.INBOX)
        assertThat(driver.subjects(account)).containsExactly(SUBJECT_A)
    }

    private companion object {
        val WORK = FolderPath.of("Work")
        const val SUBJECT_A = "Inbox message"
        const val SUBJECT_B = "Work message"
    }
}
