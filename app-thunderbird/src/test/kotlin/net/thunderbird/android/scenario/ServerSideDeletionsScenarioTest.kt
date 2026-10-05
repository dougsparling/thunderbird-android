package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * Another client changes the inbox on the server: it expunges message A and flags B `\Deleted` without expunging it.
 * When the user pulls to refresh, the app must show only C: the expunged message is gone and the deleted-but-not-
 * expunged message is hidden. The app must not expunge B itself; the server still holds B (flagged `\Deleted`) and C.
 */
class ServerSideDeletionsScenarioTest : ScenarioTest() {

    @Test
    fun `refresh after server-side deletions hides expunged and deleted messages without expunging`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(SUBJECT_A)
                message(SUBJECT_B)
                message(SUBJECT_C)
            }
        }
        val account = client.account(user)
        assertThat(driver.subjects(account)).containsExactlyInAnyOrder(SUBJECT_A, SUBJECT_B, SUBJECT_C)

        // Act
        server.deleteMessage(user, FolderPath.INBOX, SUBJECT_A, expunge = true)
        server.deleteMessage(user, FolderPath.INBOX, SUBJECT_B, expunge = false)
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.subjects(account)).containsExactly(SUBJECT_C)
        val inbox = server.stateOf(user).folder(FolderPath.INBOX)
        assertThat(inbox.subjects).containsExactlyInAnyOrder(SUBJECT_B, SUBJECT_C)
        assertThat(inbox.message(SUBJECT_B).flags).contains(SystemFlag.DELETED)
    }

    private companion object {
        const val SUBJECT_A = "Expunged elsewhere"
        const val SUBJECT_B = "Deleted elsewhere"
        const val SUBJECT_C = "Kept"
    }
}
