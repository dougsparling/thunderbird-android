package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioScope
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Another client renames the synced folder Work to Projects. When the user refreshes the folder list and then opens
 * Projects, the folder list must have lost Work and gained Projects, and Projects must show the messages A and B that
 * were in Work. The rename happens on the server, so the app must follow it rather than keep a stale Work.
 */
class FolderRenamedOnServerScenarioTest : ScenarioTest() {

    @Test
    fun `a folder renamed elsewhere shows under its new name with its messages`() = scenario {
        val user = server.user {
            folder(WORK_NAME) {
                message {
                    subject(SUBJECT_A)
                    from(SENDER)
                    text("Was in Work before it was renamed.")
                }
                message {
                    subject(SUBJECT_B)
                    from(SENDER)
                    text("Was in Work before it was renamed.")
                }
            }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, WORK)
        assertThat(subjects(account, WORK)).containsExactlyInAnyOrder(SUBJECT_A, SUBJECT_B)

        // Another client renames the folder; its messages move with it on the server.
        server.renameFolder(user, WORK, PROJECTS)

        // The user refreshes the folder list, then opens the renamed folder.
        driver.refreshFolders(account)
        driver.pullToRefresh(account, PROJECTS)

        // The server really has the renamed folder and only then the app.
        val serverPaths = server.stateOf(user).folders.map { it.path }
        assertThat(serverPaths).doesNotContain(WORK)
        assertThat(server.stateOf(user).folder(PROJECTS).messages.map { it.subject })
            .containsExactlyInAnyOrder(SUBJECT_A, SUBJECT_B)

        val folderPaths = driver.folderList(account).map { it.path }
        assertThat(folderPaths).doesNotContain(WORK)
        assertThat(folderPaths).contains(PROJECTS)
        assertThat(subjects(account, PROJECTS)).containsExactlyInAnyOrder(SUBJECT_A, SUBJECT_B)
    }

    private fun ScenarioScope.subjects(account: ClientAccount, folder: FolderPath): List<String?> =
        driver.messageList(account, folder).map(ClientMessage::subject)

    private companion object {
        const val WORK_NAME = "Work"
        const val PROJECTS_NAME = "Projects"
        val WORK = FolderPath.of(WORK_NAME)
        val PROJECTS = FolderPath.of(PROJECTS_NAME)
        const val SENDER = "erin@example.org"
        const val SUBJECT_A = "Report A"
        const val SUBJECT_B = "Report B"
    }
}
