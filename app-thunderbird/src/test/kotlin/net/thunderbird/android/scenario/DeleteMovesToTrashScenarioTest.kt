package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.prop
import assertk.assertions.single
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * Deleting an unread message from INBOX moves it to the account's Trash folder and marks it read, the default "mark
 * as read on delete" behaviour. On the server the message must be gone from INBOX and present in Trash flagged
 * `\Seen`, and the app must show it in Trash too once Trash has been synced.
 *
 * Covers GitHub #7721 / #6582.
 */
class DeleteMovesToTrashScenarioTest : ScenarioTest() {

    @Test
    fun `deleting a message moves it to trash and marks it read`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(SUBJECT)
                    from(SENDER)
                    text("Delete me.")
                }
            }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, FolderPath.INBOX)
        assertThat(driver.messageList(account, FolderPath.INBOX)).single().prop(ClientMessage::isRead).isFalse()

        driver.delete(account, FolderPath.INBOX, SUBJECT)

        // GitHub #7401 is about the app not showing a deleted message in Trash until Trash is synced. Observed here
        // (without refreshing Trash): the app already shows the message in Trash, because deleting moves it into the
        // local Trash folder right away. Only the state after refreshing Trash is asserted, as the issue may change.
        driver.pullToRefresh(account, TRASH)

        val state = server.stateOf(user)
        assertThat(state.folder(FolderPath.INBOX).messages.map { it.subject }).doesNotContain(SUBJECT)
        assertThat(state.folder(TRASH).message(SUBJECT).flags).contains(SystemFlag.SEEN)

        val trash = driver.messageList(account, TRASH)
        assertThat(trash).single().prop(ClientMessage::subject).isEqualTo(SUBJECT)
        assertThat(trash).single().prop(ClientMessage::isRead).isTrue()
    }

    private companion object {
        const val SUBJECT = "Team lunch photos"
        const val SENDER = "alice@example.org"
        val TRASH = FolderPath.of("Trash")
    }
}
