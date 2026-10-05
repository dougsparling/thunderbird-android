package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/** Moving a conversation from the threaded list moves every message of it. */
class ThreadedMoveScenarioTest : ScenarioTest() {

    @Test
    fun `moving a thread moves all its messages`() = scenario {
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
        driver.moveThread(account, FolderPath.INBOX, THREAD_NEWEST, to = WORK)
        driver.pullToRefresh(account, WORK)

        // Assert
        val serverState = server.stateOf(user)
        assertThat(serverState.folder(FolderPath.INBOX).subjects).containsExactly(UNRELATED)
        assertThat(serverState.folder(WORK).subjects).containsExactlyInAnyOrder(*THREAD_SUBJECTS.toTypedArray())
        assertThat(driver.subjects(account, WORK)).containsExactlyInAnyOrder(*THREAD_SUBJECTS.toTypedArray())
    }

    private companion object {
        val WORK = FolderPath.of("Work")
    }
}
