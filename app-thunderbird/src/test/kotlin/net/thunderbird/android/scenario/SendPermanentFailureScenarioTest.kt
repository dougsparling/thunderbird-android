package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The server rejects the recipient with a permanent error. The message stays in the outbox, the user is told, and
 * later refreshes don't try to send it again.
 */
class SendPermanentFailureScenarioTest : ScenarioTest() {

    @Test
    fun `a message the server rejects for good is kept but not retried`() = scenario {
        // Arrange
        val sender = server.user()
        val account = client.account(sender)
        val unknownRecipient = "no-such-user@${server.config.domain}"

        // Act
        driver.send(account, Composition(to = listOf(unknownRecipient), subject = SUBJECT, text = "Hi"))
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.outbox(account).map(ClientMessage::subject)).containsExactly(SUBJECT)
        assertThat(device.notifications()).hasSize(1)
        // One attempt: the refresh after the rejection doesn't send again.
        assertThat(RCPT.findAll(smtpProxy.transcript()).count()).isEqualTo(1)
    }

    private companion object {
        const val SUBJECT = "Wrong address"
        val RCPT = Regex("""C: RCPT TO""", RegexOption.IGNORE_CASE)
    }
}
