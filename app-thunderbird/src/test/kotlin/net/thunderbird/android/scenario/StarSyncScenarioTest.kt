package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
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
        val user = server.user {
            inbox {
                message {
                    subject(UNSTARRED_SUBJECT)
                    from(SENDER)
                    text("Not starred yet.")
                }
                message {
                    subject(STARRED_SUBJECT)
                    from(SENDER)
                    flags(SystemFlag.FLAGGED)
                    text("Already starred.")
                }
            }
        }
        val account = client.account(user)

        // Sync the seeded inbox before changing anything.
        driver.pullToRefresh(account, FolderPath.INBOX)

        driver.setStarred(account, FolderPath.INBOX, UNSTARRED_SUBJECT, starred = true)
        driver.setStarred(account, FolderPath.INBOX, STARRED_SUBJECT, starred = false)
        driver.pullToRefresh(account, FolderPath.INBOX)

        assertThat(server.stateOf(user).folder(FolderPath.INBOX).message(UNSTARRED_SUBJECT).flags)
            .contains(SystemFlag.FLAGGED)
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).message(STARRED_SUBJECT).flags)
            .doesNotContain(SystemFlag.FLAGGED)

        val shown = driver.messageList(account, FolderPath.INBOX).associateBy(ClientMessage::subject)
        assertThat(shown.getValue(UNSTARRED_SUBJECT).isStarred).isTrue()
        assertThat(shown.getValue(STARRED_SUBJECT).isStarred).isFalse()
    }

    private companion object {
        const val SENDER = "erin@example.org"
        const val UNSTARRED_SUBJECT = "Star this one"
        const val STARRED_SUBJECT = "Unstar this one"
    }
}
