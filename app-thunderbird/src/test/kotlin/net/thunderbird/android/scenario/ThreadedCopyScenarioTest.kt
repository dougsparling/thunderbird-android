package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/** Copying a conversation from the threaded list copies every message of it and keeps the originals. */
class ThreadedCopyScenarioTest : ScenarioTest() {

    @Test
    fun `copying a thread copies all its messages`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(UNRELATED)
                conversation()
            }
            folder(WORK.segments.single())
        }
        val account = client.account(user)

        // Act
        driver.copyThread(account, FolderPath.INBOX, THREAD_NEWEST, to = WORK)
        driver.pullToRefresh(account, WORK)

        // Assert
        val serverState = server.stateOf(user)
        val inboxSubjects = (THREAD_SUBJECTS + UNRELATED).toTypedArray()
        assertThat(serverState.folder(FolderPath.INBOX).subjects).containsExactlyInAnyOrder(*inboxSubjects)
        assertThat(serverState.folder(WORK).subjects).containsExactlyInAnyOrder(*THREAD_SUBJECTS.toTypedArray())
        assertThat(driver.subjects(account, WORK)).containsExactlyInAnyOrder(*THREAD_SUBJECTS.toTypedArray())
    }

    private companion object {
        val WORK = FolderPath.of("Work")
    }
}
