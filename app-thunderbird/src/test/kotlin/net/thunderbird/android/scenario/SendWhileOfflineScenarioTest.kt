package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * A message sent while offline waits in the outbox and goes out with the next refresh once the device is back online.
 */
class SendWhileOfflineScenarioTest : ScenarioTest() {

    @Test
    fun `a message sent offline waits in the outbox until the next refresh online`() = scenario {
        // Arrange
        val sender = server.user()
        val recipient = server.user()
        val account = client.account(sender)
        goOffline()

        // Act
        driver.send(account, Composition(to = listOf(recipient.username), subject = SUBJECT, text = "Hi"))

        // Assert
        assertThat(driver.outbox(account).map(ClientMessage::subject)).containsExactly(SUBJECT)
        assertThat(server.stateOf(recipient).folder(FolderPath.INBOX).messages).isEmpty()

        // Act
        goOnline()
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        // The server delivers to local users asynchronously.
        eventually {
            assertThat(server.stateOf(recipient).folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)
        }
        assertThat(driver.outbox(account)).isEmpty()
    }

    private companion object {
        const val SUBJECT = "Written on the train"
    }
}
