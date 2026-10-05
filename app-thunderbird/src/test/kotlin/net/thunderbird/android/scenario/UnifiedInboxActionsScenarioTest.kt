package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccountMessage
import net.thunderbird.android.scenario.harness.MessageSelector
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.SelectionAction
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * The unified inbox shows both accounts' mail. Acting on a selection that spans both accounts changes each message on
 * its own account's server and leaves the other messages alone.
 */
class UnifiedInboxActionsScenarioTest : ScenarioTest() {

    @Test
    fun `actions in the unified inbox reach each account's server`() = scenario {
        // Arrange
        val firstUser = server.user {
            inbox {
                message(FIRST_READ)
                message(FIRST_DELETED)
            }
        }
        val secondUser = server.user {
            inbox {
                message(SECOND_READ)
                message(SECOND_DELETED)
            }
        }
        val first = client.account(firstUser)
        val second = client.account(secondUser)
        assertThat(driver.unifiedInbox().map { it.message.subject })
            .containsExactlyInAnyOrder(FIRST_READ, FIRST_DELETED, SECOND_READ, SECOND_DELETED)

        // Act
        driver.actOnSelection(
            listOf(
                MessageSelector(first, FolderPath.INBOX, FIRST_READ),
                MessageSelector(second, FolderPath.INBOX, SECOND_READ),
            ),
            SelectionAction.MARK_READ,
        )
        driver.actOnSelection(
            listOf(
                MessageSelector(first, FolderPath.INBOX, FIRST_DELETED),
                MessageSelector(second, FolderPath.INBOX, SECOND_DELETED),
            ),
            SelectionAction.DELETE,
        )

        // Assert
        val firstState = server.stateOf(firstUser)
        val secondState = server.stateOf(secondUser)
        assertThat(firstState.folder(FolderPath.INBOX).message(FIRST_READ).flags).contains(SystemFlag.SEEN)
        assertThat(secondState.folder(FolderPath.INBOX).message(SECOND_READ).flags).contains(SystemFlag.SEEN)
        assertThat(firstState.folder(TRASH).subjects).containsExactly(FIRST_DELETED)
        assertThat(secondState.folder(TRASH).subjects).containsExactly(SECOND_DELETED)
        assertThat(firstState.folder(FolderPath.INBOX).subjects).doesNotContain(FIRST_DELETED)
        val unified = driver.unifiedInbox()
        assertThat(unified.map(ClientAccountMessage::accountEmail))
            .containsExactlyInAnyOrder(first.email, second.email)
        assertThat(unified.filterNot { it.message.isRead }).isEmpty()
    }

    private companion object {
        const val FIRST_READ = "Work: read me"
        const val FIRST_DELETED = "Work: delete me"
        const val SECOND_READ = "Home: read me"
        const val SECOND_DELETED = "Home: delete me"
    }
}
