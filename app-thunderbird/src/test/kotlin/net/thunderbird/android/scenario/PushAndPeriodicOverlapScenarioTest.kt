package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * With push and periodic sync both on, new mail that push and a periodic sync handle at about the same time shows up
 * exactly once.
 */
class PushAndPeriodicOverlapScenarioTest : ScenarioTest() {

    @Test
    fun `push and periodic sync together don't duplicate mail`() = scenario {
        // Arrange
        val user = server.user { inbox { message(OLD) } }
        val account = client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES)
        device.advanceTime(1.minutes)
        driver.enablePush(account, FolderPath.INBOX)
        awaitAppListening()

        // Act
        server.deliver(user) { inbox { message(NEW) } }
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)

        // Assert
        eventually { assertThat(driver.subjects(account)).containsExactly(NEW, OLD) }
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val OLD = "Already there"
        const val NEW = "Pushed and polled"
    }
}
