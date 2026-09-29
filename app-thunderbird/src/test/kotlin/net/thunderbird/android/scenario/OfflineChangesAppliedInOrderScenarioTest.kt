package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioScope
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * While offline the user makes four changes in INBOX: marks A read, stars B, deletes C and moves D to Work. Once the
 * app is back online and the folders have been refreshed, all four changes must have reached the server (A read, B
 * starred, C in Trash but not INBOX, D in Work but not INBOX) and the lists the user sees must match it.
 */
class OfflineChangesAppliedInOrderScenarioTest : ScenarioTest() {

    @Test
    fun `changes made offline all reach the server and the lists match it`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(READ_SUBJECT)
                    from(SENDER)
                    text("Mark me read while offline.")
                }
                message {
                    subject(STAR_SUBJECT)
                    from(SENDER)
                    text("Star me while offline.")
                }
                message {
                    subject(DELETE_SUBJECT)
                    from(SENDER)
                    text("Delete me while offline.")
                }
                message {
                    subject(MOVE_SUBJECT)
                    from(SENDER)
                    text("Move me while offline.")
                }
            }
            folder("Work")
        }
        val account = client.account(user)
        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.refreshFolders(account)

        goOffline()
        driver.markRead(account, FolderPath.INBOX, READ_SUBJECT)
        driver.setStarred(account, FolderPath.INBOX, STAR_SUBJECT, starred = true)
        driver.delete(account, FolderPath.INBOX, DELETE_SUBJECT)
        driver.move(account, FolderPath.INBOX, MOVE_SUBJECT, to = WORK)
        goOnline()

        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.pullToRefresh(account, WORK)
        driver.pullToRefresh(account, TRASH)

        // The server got all four changes: A and B stay in INBOX with their new flags, C is only in Trash, D only in
        // Work.
        val state = server.stateOf(user)
        assertThat(state.folder(FolderPath.INBOX).messages.map { it.subject })
            .containsExactlyInAnyOrder(READ_SUBJECT, STAR_SUBJECT)
        assertThat(state.folder(FolderPath.INBOX).message(READ_SUBJECT).flags).contains(SystemFlag.SEEN)
        assertThat(state.folder(FolderPath.INBOX).message(STAR_SUBJECT).flags).contains(SystemFlag.FLAGGED)
        assertThat(state.folder(WORK).messages.map { it.subject }).containsExactly(MOVE_SUBJECT)
        assertThat(state.folder(TRASH).messages.map { it.subject }).containsExactly(DELETE_SUBJECT)

        // What the user sees matches the server.
        assertThat(subjects(account, FolderPath.INBOX))
            .containsExactlyInAnyOrder(READ_SUBJECT, STAR_SUBJECT)
        assertThat(message(account, FolderPath.INBOX, READ_SUBJECT)).prop(ClientMessage::isRead).isTrue()
        assertThat(message(account, FolderPath.INBOX, STAR_SUBJECT)).prop(ClientMessage::isStarred).isTrue()
        assertThat(subjects(account, WORK)).containsExactly(MOVE_SUBJECT)
        assertThat(subjects(account, TRASH)).containsExactly(DELETE_SUBJECT)
    }

    private fun ScenarioScope.subjects(account: ClientAccount, folder: FolderPath): List<String?> =
        driver.messageList(account, folder).map(ClientMessage::subject)

    private fun ScenarioScope.message(account: ClientAccount, folder: FolderPath, subject: String): ClientMessage =
        driver.messageList(account, folder).single { it.subject == subject }

    private companion object {
        const val SENDER = "bob@example.org"
        const val READ_SUBJECT = "Read me offline"
        const val STAR_SUBJECT = "Star me offline"
        const val DELETE_SUBJECT = "Delete me offline"
        const val MOVE_SUBJECT = "Move me offline"
        val WORK = FolderPath.of("Work")
        val TRASH = FolderPath.of("Trash")
    }
}
