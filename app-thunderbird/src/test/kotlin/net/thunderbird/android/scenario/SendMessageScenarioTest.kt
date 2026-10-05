package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * Sending a message delivers it to the recipient and keeps a read copy in the sender's Sent folder, on the server and
 * in the app; nothing stays behind in the outbox.
 */
class SendMessageScenarioTest : ScenarioTest() {

    @Test
    fun `a sent message reaches the recipient and the sent folder`() = scenario {
        // Arrange
        val sender = server.user()
        val recipient = server.user()
        val account = client.account(sender)

        // Act
        driver.send(account, Composition(to = listOf(recipient.username), subject = SUBJECT, text = TEXT))

        // Assert
        // The server delivers to local users asynchronously.
        eventually {
            assertThat(server.stateOf(recipient).folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)
        }
        val sentOnServer = server.stateOf(sender).folder(SENT).message(SUBJECT)
        assertThat(sentOnServer.flags).contains(SystemFlag.SEEN)
        assertThat(driver.subjects(account, SENT)).containsExactly(SUBJECT)
        assertThat(driver.outbox(account)).isEmpty()
    }

    private companion object {
        val SENT = FolderPath.of("Sent")
        const val SUBJECT = "Lunch on Friday?"
        const val TEXT = "The usual place at noon."
    }
}
