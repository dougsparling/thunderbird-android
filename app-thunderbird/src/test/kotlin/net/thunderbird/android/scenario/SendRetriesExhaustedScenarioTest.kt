package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The SMTP server can't be reached for five attempts in a row. After the fifth failed attempt the app gives up on the
 * message: it stays in the outbox and isn't sent even once the server is reachable again.
 */
class SendRetriesExhaustedScenarioTest : ScenarioTest() {

    @Test
    fun `the app stops retrying a message after five failed attempts`() = scenario {
        // Arrange
        val sender = server.user()
        val recipient = server.user()
        val account = client.account(sender)
        smtpNetwork { refuseConnections() }

        // Act
        // The first attempt is part of sending; each refresh makes another.
        driver.send(account, Composition(to = listOf(recipient.username), subject = SUBJECT, text = "Hi"))
        repeat(MAX_SEND_ATTEMPTS - 1) { driver.pullToRefresh(account, FolderPath.INBOX) }
        smtpNetwork { }
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        // The app never got as far as handing the message to the server.
        assertThat(smtpProxy.transcript()).doesNotContain("C: DATA")
        assertThat(driver.outbox(account).map(ClientMessage::subject)).containsExactly(SUBJECT)
    }

    private companion object {
        const val SUBJECT = "Never mind"
        const val MAX_SEND_ATTEMPTS = 5
    }
}
