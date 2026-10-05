package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Another client renames the synced folder Work to Projects. When the user refreshes the folder list and then opens
 * Projects, the folder list must have lost Work and gained Projects, and Projects must show the messages A and B that
 * were in Work. The rename happens on the server, so the app must follow it rather than keep a stale Work.
 */
class FolderRenamedOnServerScenarioTest : ScenarioTest() {

    @Test
    fun `a folder renamed elsewhere shows under its new name with its messages`() = scenario {
        // Arrange
        val user = server.user {
            folder("Work") {
                message(SUBJECT_A)
                message(SUBJECT_B)
            }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, WORK)

        // Act
        server.renameFolder(user, WORK, PROJECTS)
        driver.refreshFolders(account)
        driver.pullToRefresh(account, PROJECTS)

        // Assert
        val folderPaths = driver.folderList(account).map { it.path }
        assertThat(folderPaths).doesNotContain(WORK)
        assertThat(folderPaths).contains(PROJECTS)
        assertThat(driver.subjects(account, PROJECTS)).containsExactlyInAnyOrder(SUBJECT_A, SUBJECT_B)
    }

    private companion object {
        val WORK = FolderPath.of("Work")
        val PROJECTS = FolderPath.of("Projects")
        const val SUBJECT_A = "Report A"
        const val SUBJECT_B = "Report B"
    }
}
