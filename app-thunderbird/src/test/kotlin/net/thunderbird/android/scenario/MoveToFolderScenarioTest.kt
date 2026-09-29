package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.prop
import assertk.assertions.single
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Moving a message into a folder must put it there and take it out of where it was: after the move and a refresh the
 * server has the message in Work and not in INBOX, and the app shows the same.
 */
class MoveToFolderScenarioTest : ScenarioTest() {

    @Test
    fun `moving a message into a folder puts it there and removes it from the inbox`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(SUBJECT)
                    from(SENDER)
                    text("Move me to Work.")
                }
            }
            folder("Work")
        }
        val account = client.account(user)
        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.refreshFolders(account)

        driver.move(account, FolderPath.INBOX, SUBJECT, WORK)

        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.pullToRefresh(account, WORK)

        val state = server.stateOf(user)
        assertThat(state.folder(FolderPath.INBOX).messages).isEmpty()
        assertThat(state.folder(WORK).messages.map { it.subject }).containsExactly(SUBJECT)

        assertThat(driver.messageList(account, FolderPath.INBOX)).isEmpty()
        assertThat(driver.messageList(account, WORK)).single().prop(ClientMessage::subject).isEqualTo(SUBJECT)
    }

    private companion object {
        const val SUBJECT = "Project plan"
        const val SENDER = "erin@example.org"
        val WORK = FolderPath.of("Work")
    }
}
