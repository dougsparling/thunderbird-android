package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
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
 * Deleting an unread message from INBOX moves it to the account's Trash folder and marks it read, the default "mark
 * as read on delete" behaviour. On the server the message must be gone from INBOX and present in Trash flagged
 * `\Seen`, and the app must show it in Trash too once Trash has been synced.
 *
 * Covers GitHub #7721 / #6582.
 */
class DeleteMovesToTrashScenarioTest : ScenarioTest() {

    @Test
    fun `deleting a message moves it to trash and marks it read`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
        }
        val account = client.account(user)

        // Act
        driver.delete(account, FolderPath.INBOX, SUBJECT)
        // GitHub #7401 is about the app not showing a deleted message in Trash until Trash is synced. Observed here
        // (without refreshing Trash): the app already shows the message in Trash, because deleting moves it into the
        // local Trash folder right away. Only the state after refreshing Trash is asserted, as the issue may change.
        driver.pullToRefresh(account, TRASH)

        // Assert
        val state = server.stateOf(user)
        assertThat(state.folder(FolderPath.INBOX).subjects).isEmpty()
        assertThat(state.folder(TRASH).message(SUBJECT).flags).contains(SystemFlag.SEEN)
        assertThat(driver.subjects(account, TRASH)).containsExactly(SUBJECT)
        assertThat(driver.message(account, SUBJECT, TRASH)).prop(ClientMessage::isRead).isTrue()
    }

    private companion object {
        const val SUBJECT = "Team lunch photos"
    }
}
