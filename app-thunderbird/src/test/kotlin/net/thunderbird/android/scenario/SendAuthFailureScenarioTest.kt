package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The outgoing server rejects the account's password. The message stays in the outbox and the user is told; once the
 * user fixes the outgoing password, the next refresh sends it.
 */
class SendAuthFailureScenarioTest : ScenarioTest() {

    @Test
    fun `a wrong SMTP password keeps the message until the user fixes it`() = scenario {
        // Arrange
        val sender = server.user()
        val recipient = server.user()
        val account = client.account(sender, smtpPassword = "wrong-password")

        // Act
        driver.send(account, Composition(to = listOf(recipient.username), subject = SUBJECT, text = "Hi"))

        // Assert
        assertThat(driver.outbox(account).map(ClientMessage::subject)).containsExactly(SUBJECT)
        assertThat(device.notifications()).isNotEmpty()

        // Act
        driver.updateOutgoingPassword(account, sender.password)
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        // The server delivers to local users asynchronously.
        eventually {
            assertThat(server.stateOf(recipient).folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)
        }
        assertThat(driver.outbox(account)).isEmpty()
    }

    private companion object {
        const val SUBJECT = "Second try"
    }
}
