package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.message
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * Starring one message and unstarring another must reach the server, and the app must show the server's stars after a
 * refresh.
 *
 * The inbox starts with one unstarred message (A) and one already-starred message (B). The user stars A and unstars B,
 * then pulls to refresh. Afterwards the server has A flagged and B unflagged, and the app shows A starred and B not.
 */
class StarSyncScenarioTest : ScenarioTest() {

    @Test
    fun `starring one message and unstarring another syncs both ways`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(UNSTARRED_SUBJECT)
                message(STARRED_SUBJECT) { flags(SystemFlag.FLAGGED) }
            }
        }
        val account = client.account(user)

        // Act
        driver.setStarred(account, FolderPath.INBOX, UNSTARRED_SUBJECT, starred = true)
        driver.setStarred(account, FolderPath.INBOX, STARRED_SUBJECT, starred = false)
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        val inbox = server.stateOf(user).folder(FolderPath.INBOX)
        assertThat(inbox.message(UNSTARRED_SUBJECT).flags).contains(SystemFlag.FLAGGED)
        assertThat(inbox.message(STARRED_SUBJECT).flags).doesNotContain(SystemFlag.FLAGGED)
        assertThat(driver.message(account, UNSTARRED_SUBJECT)).prop(ClientMessage::isStarred).isTrue()
        assertThat(driver.message(account, STARRED_SUBJECT)).prop(ClientMessage::isStarred).isFalse()
    }

    private companion object {
        const val UNSTARRED_SUBJECT = "Star this one"
        const val STARRED_SUBJECT = "Unstar this one"
    }
}
