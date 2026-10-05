package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.android.scenario.harness.message
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * While offline the user makes four changes in INBOX: marks A read, stars B, deletes C and moves D to Work. Once the
 * app is back online and the folders have been refreshed, all four changes must have reached the server (A read, B
 * starred, C in Trash but not INBOX, D in Work but not INBOX) and the lists the user sees must match it.
 *
 * The order in which the app sends the changes isn't asserted: they touch different messages, so any order gives the
 * user the same result. [ReadThenArchiveOrderScenarioTest] covers two changes to one message, where order matters.
 */
class OfflineChangesAllReachServerScenarioTest : ScenarioTest() {

    @Test
    fun `changes made offline all reach the server and the lists match it`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(READ_SUBJECT)
                message(STAR_SUBJECT)
                message(DELETE_SUBJECT)
                message(MOVE_SUBJECT)
            }
            folder("Work")
        }
        val account = client.account(user)

        // Act
        goOffline()
        driver.markRead(account, FolderPath.INBOX, READ_SUBJECT)
        driver.setStarred(account, FolderPath.INBOX, STAR_SUBJECT, starred = true)
        driver.delete(account, FolderPath.INBOX, DELETE_SUBJECT)
        driver.move(account, FolderPath.INBOX, MOVE_SUBJECT, to = WORK)
        goOnline()
        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.pullToRefresh(account, WORK)
        driver.pullToRefresh(account, TRASH)

        // Assert
        val state = server.stateOf(user)
        val inbox = state.folder(FolderPath.INBOX)
        assertThat(inbox.subjects).containsExactlyInAnyOrder(READ_SUBJECT, STAR_SUBJECT)
        assertThat(inbox.message(READ_SUBJECT).flags).contains(SystemFlag.SEEN)
        assertThat(inbox.message(STAR_SUBJECT).flags).contains(SystemFlag.FLAGGED)
        assertThat(state.folder(WORK).subjects).containsExactly(MOVE_SUBJECT)
        assertThat(state.folder(TRASH).subjects).containsExactly(DELETE_SUBJECT)

        assertThat(driver.subjects(account)).containsExactlyInAnyOrder(READ_SUBJECT, STAR_SUBJECT)
        assertThat(driver.message(account, READ_SUBJECT)).prop(ClientMessage::isRead).isTrue()
        assertThat(driver.message(account, STAR_SUBJECT)).prop(ClientMessage::isStarred).isTrue()
        assertThat(driver.subjects(account, WORK)).containsExactly(MOVE_SUBJECT)
        assertThat(driver.subjects(account, TRASH)).containsExactly(DELETE_SUBJECT)
    }

    private companion object {
        const val READ_SUBJECT = "Read me offline"
        const val STAR_SUBJECT = "Star me offline"
        const val DELETE_SUBJECT = "Delete me offline"
        const val MOVE_SUBJECT = "Move me offline"
        val WORK = FolderPath.of("Work")
    }
}
