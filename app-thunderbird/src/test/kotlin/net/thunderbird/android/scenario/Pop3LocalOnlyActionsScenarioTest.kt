package net.thunderbird.android.scenario

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.MailProtocol
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.android.scenario.harness.message
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * POP3 has no flags or folders on the server: marking a message read or starring it only changes the app's copy, and
 * the app doesn't offer to move a message.
 */
class Pop3LocalOnlyActionsScenarioTest : ScenarioTest() {

    @Test
    fun `POP3 flags stay local and moving isn't offered`() = scenario {
        // Arrange
        val user = server.user { inbox { message(SUBJECT) } }
        val account = client.account(user, protocol = MailProtocol.POP3)
        val serverBefore = server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags

        // Act
        driver.markRead(account, FolderPath.INBOX, SUBJECT)
        driver.setStarred(account, FolderPath.INBOX, SUBJECT, starred = true)

        // Assert
        assertThat(driver.message(account, SUBJECT)).prop(ClientMessage::isRead).isTrue()
        assertThat(driver.message(account, SUBJECT)).prop(ClientMessage::isStarred).isTrue()
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags).isEqualTo(serverBefore)
        assertFailure { driver.move(account, FolderPath.INBOX, SUBJECT, to = TRASH) }
            .messageContains("can't move messages")
    }

    private companion object {
        const val SUBJECT = "Local flags only"
    }
}
