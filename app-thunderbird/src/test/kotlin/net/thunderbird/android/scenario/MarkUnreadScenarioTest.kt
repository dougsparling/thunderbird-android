package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
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
 * Turning a read message back to unread must reach the server, and the app must show it unread after a refresh.
 *
 * INBOX starts with one message that already carries \Seen. The user marks it unread and pulls to refresh. Afterwards
 * the server's copy lacks \Seen and the app shows the message as unread.
 */
class MarkUnreadScenarioTest : ScenarioTest() {

    @Test
    fun `marking a read message unread syncs to the server and back`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(SUBJECT)
                    from(SENDER)
                    flags(SystemFlag.SEEN)
                    text("Already read.")
                }
            }
        }
        val account = client.account(user)

        // Sync the seeded inbox before changing anything, and guard against a vacuous pass: the message really starts
        // read both on the server and in the app.
        driver.pullToRefresh(account, FolderPath.INBOX)
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags)
            .contains(SystemFlag.SEEN)
        assertThat(driver.messageList(account, FolderPath.INBOX)).single()
            .prop(ClientMessage::isRead).isTrue()

        driver.markUnread(account, FolderPath.INBOX, SUBJECT)
        driver.pullToRefresh(account, FolderPath.INBOX)

        assertThat(server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags)
            .doesNotContain(SystemFlag.SEEN)
        assertThat(driver.messageList(account, FolderPath.INBOX)).single()
            .prop(ClientMessage::isRead).isFalse()
    }

    private companion object {
        const val SENDER = "grace@example.org"
        const val SUBJECT = "Read this again"
    }
}
