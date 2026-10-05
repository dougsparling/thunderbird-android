package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/** A message waiting in the outbox survives the app being killed and is sent once the device is online again. */
class OutboxSurvivesRestartScenarioTest : ScenarioTest() {

    @Test
    fun `the outbox survives a restart`() = scenario {
        // Arrange
        val sender = server.user()
        val recipient = server.user()
        val account = client.account(sender)
        goOffline()
        driver.send(account, Composition(to = listOf(recipient.username), subject = SUBJECT, text = "Hi"))

        // Act
        restartApp()

        // Assert
        assertThat(driver.outbox(account).map(ClientMessage::subject)).containsExactly(SUBJECT)

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
        const val SUBJECT = "Written before the phone rebooted"
    }
}
