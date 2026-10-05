package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The app threads messages per folder. A reply the user keeps in another folder isn't part of the inbox's thread, so
 * deleting the thread from the inbox leaves the reply where it is.
 */
class ThreadAcrossFoldersScenarioTest : ScenarioTest() {

    @Test
    fun `deleting a thread only affects the folder it is shown in`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(ROOT) { messageId("<question@example.org>") }
                message(REPLY_IN_INBOX) {
                    messageId("<answer@example.org>")
                    header("In-Reply-To", "<question@example.org>")
                }
            }
            folder(WORK.segments.single()) {
                message(REPLY_ELSEWHERE) {
                    header("In-Reply-To", "<answer@example.org>")
                    header("References", "<question@example.org> <answer@example.org>")
                }
            }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, WORK)

        // Act
        driver.deleteThread(account, FolderPath.INBOX, REPLY_IN_INBOX)

        // Assert
        val serverState = server.stateOf(user)
        assertThat(serverState.folder(WORK).subjects).containsExactly(REPLY_ELSEWHERE)
        assertThat(serverState.folder(TRASH).subjects.sortedBy { it }).containsExactly(ROOT, REPLY_IN_INBOX)
    }

    private companion object {
        val WORK = FolderPath.of("Work")
        const val ROOT = "Question"
        const val REPLY_IN_INBOX = "Re: Question"
        const val REPLY_ELSEWHERE = "Re: Re: Question"
    }
}
