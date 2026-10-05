package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientThread
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/** Deleting a conversation from the threaded list moves every message of it to Trash and leaves other mail alone. */
class ThreadedDeleteScenarioTest : ScenarioTest() {

    @Test
    fun `deleting a thread moves all its messages to trash`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(UNRELATED)
                conversation()
            }
        }
        val account = client.account(user)
        assertThat(driver.threadList(account, FolderPath.INBOX).map(ClientThread::messageCount)).containsExactly(3, 1)

        // Act
        driver.deleteThread(account, FolderPath.INBOX, THREAD_NEWEST)

        // Assert
        val serverState = server.stateOf(user)
        assertThat(serverState.folder(FolderPath.INBOX).subjects).containsExactly(UNRELATED)
        assertThat(serverState.folder(TRASH).subjects).containsExactlyInAnyOrder(*THREAD_SUBJECTS.toTypedArray())
        assertThat(driver.subjects(account, FolderPath.INBOX)).containsExactly(UNRELATED)
    }
}
