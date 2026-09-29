package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isFalse
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.message
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
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) { flags(SystemFlag.SEEN) } }
        }
        val account = client.account(user)

        // Act
        driver.markUnread(account, FolderPath.INBOX, SUBJECT)
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags).doesNotContain(SystemFlag.SEEN)
        assertThat(driver.message(account, SUBJECT)).prop(ClientMessage::isRead).isFalse()
    }

    private companion object {
        const val SUBJECT = "Read this again"
    }
}
