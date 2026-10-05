package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Push is listening for new mail when the device loses its network. Mail arrives while the device is offline. When the
 * device comes back online, push must reconnect on its own, fetch the missed mail without a pull to refresh, and go
 * back to listening for new mail.
 */
class PushAcrossOfflineScenarioTest : ScenarioTest() {

    @Test
    fun `pushed mail arrives after the device comes back online`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(FIRST_SUBJECT) }
        }
        val account = client.account(user)
        driver.enablePush(account, FolderPath.INBOX)
        awaitAppListening()

        // Act
        goOffline()
        server.deliver(user) {
            inbox { message(SECOND_SUBJECT) }
        }
        goOnline()

        // Assert
        // Push reconnects as soon as the network returns, without waiting for a retry timer: no time passes.
        eventually {
            assertThat(driver.subjects(account)).containsExactly(SECOND_SUBJECT, FIRST_SUBJECT)
        }
        awaitAppListening()
    }

    private companion object {
        const val FIRST_SUBJECT = "Already there"
        const val SECOND_SUBJECT = "Delivered while offline"
    }
}
