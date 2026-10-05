package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isTrue
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/** Marking a conversation as read from the threaded list marks every message of it read on the server. */
class ThreadedMarkReadScenarioTest : ScenarioTest() {

    @Test
    fun `marking a thread read marks all its messages read`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(UNRELATED)
                conversation()
            }
        }
        val account = client.account(user)

        // Act
        driver.setThreadRead(account, FolderPath.INBOX, THREAD_NEWEST, read = true)

        // Assert
        val inbox = server.stateOf(user).folder(FolderPath.INBOX)
        THREAD_SUBJECTS.forEach { subject -> assertThat(inbox.message(subject).flags).contains(SystemFlag.SEEN) }
        assertThat(inbox.message(UNRELATED).flags).doesNotContain(SystemFlag.SEEN)
        assertThat(driver.threadList(account, FolderPath.INBOX).single { it.subject == THREAD_NEWEST }.isRead).isTrue()
    }
}
