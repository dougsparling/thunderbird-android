package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * Changes made offline survive the app being killed: after a restart and once the device is online again, the next
 * refresh sends all of them to the server.
 */
class PendingChangesSurviveRestartScenarioTest : ScenarioTest() {

    @Test
    fun `offline changes reach the server after the app restarts`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(READ)
                message(STARRED)
                message(DELETED)
                message(MOVED)
            }
            folder(WORK.segments.single())
        }
        val account = client.account(user)
        goOffline()
        driver.markRead(account, FolderPath.INBOX, READ)
        driver.setStarred(account, FolderPath.INBOX, STARRED, starred = true)
        driver.delete(account, FolderPath.INBOX, DELETED)
        driver.move(account, FolderPath.INBOX, MOVED, to = WORK)

        // Act
        restartApp()
        goOnline()
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        val serverState = server.stateOf(user)
        val inbox = serverState.folder(FolderPath.INBOX)
        assertThat(inbox.subjects).containsExactlyInAnyOrder(READ, STARRED)
        assertThat(inbox.message(READ).flags).contains(SystemFlag.SEEN)
        assertThat(inbox.message(STARRED).flags).contains(SystemFlag.FLAGGED)
        assertThat(serverState.folder(TRASH).subjects).containsExactly(DELETED)
        assertThat(serverState.folder(WORK).subjects).containsExactly(MOVED)
    }

    private companion object {
        val WORK = FolderPath.of("Work")
        const val READ = "Mark me read"
        const val STARRED = "Star me"
        const val DELETED = "Delete me"
        const val MOVED = "Move me"
    }
}
