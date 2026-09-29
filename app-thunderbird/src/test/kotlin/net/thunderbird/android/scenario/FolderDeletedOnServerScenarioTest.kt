package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioScope
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Another client deletes the synced folder Work, which held message B, while the inbox holds message A. When the user
 * refreshes the folder list and then pulls to refresh the inbox, the app must keep working (no dead sync thread), the
 * folder list must have lost Work and still have INBOX, and the inbox must still show A.
 */
class FolderDeletedOnServerScenarioTest : ScenarioTest() {

    @Test
    fun `a folder deleted elsewhere disappears from the list without breaking the inbox`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(SUBJECT_A)
                    from(SENDER)
                    text("Stays in the inbox.")
                }
            }
            folder(WORK_NAME) {
                message {
                    subject(SUBJECT_B)
                    from(SENDER)
                    text("Goes with the deleted folder.")
                }
            }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.pullToRefresh(account, WORK)
        assertThat(inboxSubjects(account)).containsExactly(SUBJECT_A)
        assertThat(subjects(account, WORK)).containsExactly(SUBJECT_B)

        // Another client deletes Work with its messages.
        server.deleteFolder(user, WORK)

        // The user refreshes the folder list, then pulls to refresh the inbox.
        driver.refreshFolders(account)
        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.awaitIdle()

        // The server really lost Work; the app must follow and keep working.
        val serverPaths = server.stateOf(user).folders.map { it.path }
        assertThat(serverPaths).doesNotContain(WORK)

        val folderPaths = driver.folderList(account).map { it.path }
        assertThat(folderPaths).doesNotContain(WORK)
        assertThat(folderPaths).contains(FolderPath.INBOX)
        assertThat(inboxSubjects(account)).containsExactly(SUBJECT_A)
    }

    private fun ScenarioScope.inboxSubjects(account: ClientAccount): List<String?> =
        driver.messageList(account, FolderPath.INBOX).map(ClientMessage::subject)

    private fun ScenarioScope.subjects(account: ClientAccount, folder: FolderPath): List<String?> =
        driver.messageList(account, folder).map(ClientMessage::subject)

    private companion object {
        const val WORK_NAME = "Work"
        val WORK = FolderPath.of(WORK_NAME)
        const val SENDER = "erin@example.org"
        const val SUBJECT_A = "Inbox message"
        const val SUBJECT_B = "Work message"
    }
}
