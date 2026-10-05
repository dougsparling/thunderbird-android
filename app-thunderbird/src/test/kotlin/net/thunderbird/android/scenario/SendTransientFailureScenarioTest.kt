package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The SMTP connection drops while sending. The message stays in the outbox and the user is told sending failed; the
 * next attempt sends it and the failure notification goes away.
 */
class SendTransientFailureScenarioTest : ScenarioTest() {

    @Test
    fun `a dropped SMTP connection leaves the message in the outbox for the next attempt`() = scenario {
        // Arrange
        val sender = server.user()
        val recipient = server.user()
        val account = client.account(sender)
        smtpNetwork { onConnect { disconnect() }.once() }

        // Act
        driver.send(account, Composition(to = listOf(recipient.username), subject = SUBJECT, text = "Hi"))

        // Assert
        assertThat(driver.outbox(account).map(ClientMessage::subject)).containsExactly(SUBJECT)
        assertThat(smtpProxy.transcript()).doesNotContain("C: DATA")
        assertThat(device.notifications()).hasSize(1)

        // Act
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        // The server delivers to local users asynchronously.
        eventually {
            assertThat(server.stateOf(recipient).folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)
        }
        assertThat(driver.outbox(account)).isEmpty()
        assertThat(device.notifications()).isEmpty()
    }

    private companion object {
        const val SUBJECT = "Try again"
    }
}
