package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * With push enabled, mail delivered to INBOX shows up in the app on its own, without a pull to refresh or periodic
 * sync.
 */
class PushScenarioTest : ScenarioTest() {

    @Test
    fun `pushed mail shows up without the user refreshing`() = scenario {
        // Arrange
        val user = server.user { inbox() }
        // No check interval, so neither periodic sync nor the user will fetch the new message; only push can.
        val account = client.account(user)
        driver.enablePush(account, FolderPath.INBOX)
        awaitAppListening()

        // Act
        server.deliver(user) {
            inbox { message(SUBJECT) }
        }

        // Assert
        eventually {
            assertThat(driver.subjects(account)).containsExactly(SUBJECT)
        }
    }

    private companion object {
        const val SUBJECT = "Pushed to you"
    }
}
