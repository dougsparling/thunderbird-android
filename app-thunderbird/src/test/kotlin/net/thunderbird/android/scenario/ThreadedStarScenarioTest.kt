package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/** Starring a conversation from the threaded list stars every message of it on the server. */
class ThreadedStarScenarioTest : ScenarioTest() {

    @Test
    fun `starring a thread stars all its messages`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(UNRELATED)
                conversation()
            }
        }
        val account = client.account(user)

        // Act
        driver.setThreadStarred(account, FolderPath.INBOX, THREAD_NEWEST, starred = true)

        // Assert
        val inbox = server.stateOf(user).folder(FolderPath.INBOX)
        THREAD_SUBJECTS.forEach { subject -> assertThat(inbox.message(subject).flags).contains(SystemFlag.FLAGGED) }
        assertThat(inbox.message(UNRELATED).flags).doesNotContain(SystemFlag.FLAGGED)
    }
}
