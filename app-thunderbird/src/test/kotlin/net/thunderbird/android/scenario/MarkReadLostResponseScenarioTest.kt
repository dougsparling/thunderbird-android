package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.message
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * The server applies a "mark as read" but the connection drops before the app sees the response. The app must not
 * lose the change: after the next sync the message is read both on the server and in the app.
 */
class MarkReadLostResponseScenarioTest : ScenarioTest() {

    @Test
    fun `mark as read survives losing the server's response`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
        }
        val account = client.account(user)
        // The legacy code sends "UID STORE <uid> +FLAGS.SILENT (\Seen)" for this.
        network {
            imap.onCommand("UID STORE").afterServerResponds { disconnect() }.once()
        }

        // Act
        driver.markRead(account, FolderPath.INBOX, SUBJECT)
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        // Guards against a vacuous pass if the app stops sending UID STORE and the rule never fires.
        assertThat(proxy.transcript()).contains("!! disconnect (rule: onCommand UID STORE afterServerResponds)")
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags).contains(SystemFlag.SEEN)
        assertThat(driver.message(account, SUBJECT)).prop(ClientMessage::isRead).isTrue()
    }

    private companion object {
        const val SUBJECT = "Quarterly report"
    }
}
