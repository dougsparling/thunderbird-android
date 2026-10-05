package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/** A message left in the outbox while offline is sent by the next periodic sync, without the user doing anything. */
class PeriodicSyncSendsOutboxScenarioTest : ScenarioTest() {

    @Test
    fun `periodic sync sends what is waiting in the outbox`() = scenario {
        // Arrange
        val sender = server.user()
        val recipient = server.user()
        val account = client.account(sender, checkIntervalMinutes = CHECK_INTERVAL_MINUTES)
        device.advanceTime(1.minutes)
        goOffline()
        driver.send(account, Composition(to = listOf(recipient.username), subject = SUBJECT, text = "Hi"))
        goOnline()

        // Act
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)

        // Assert
        // The server delivers to local users asynchronously.
        eventually {
            assertThat(server.stateOf(recipient).folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)
        }
        assertThat(driver.outbox(account)).isEmpty()
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val SUBJECT = "Sent in the background"
    }
}
