package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccountSettings
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * With "upload sent messages" off, a sent message is delivered but no copy is kept: the server's Sent folder stays
 * empty, and so does the app's (the local copy is deleted after sending).
 */
class SendWithoutSentUploadScenarioTest : ScenarioTest() {

    @Test
    fun `without sent upload the app keeps no copy of a sent message`() = scenario {
        // Arrange
        val sender = server.user()
        val recipient = server.user()
        val account = client.account(sender, settings = ClientAccountSettings(uploadSentMessages = false))

        // Act
        driver.send(account, Composition(to = listOf(recipient.username), subject = SUBJECT, text = "Hi"))

        // Assert
        // The server delivers to local users asynchronously.
        eventually {
            assertThat(server.stateOf(recipient).folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)
        }
        assertThat(server.stateOf(sender).folder(SENT).messages).isEmpty()
        assertThat(driver.subjects(account, SENT)).isEmpty()
        assertThat(driver.outbox(account)).isEmpty()
    }

    private companion object {
        val SENT = FolderPath.of("Sent")
        const val SUBJECT = "No copy needed"
    }
}
