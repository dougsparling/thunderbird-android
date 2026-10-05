package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects

/** One account that can't log in doesn't keep the others from fetching mail when all accounts sync. */
class AccountsSyncIndependentlyScenarioTest : ScenarioTest() {

    @Test
    fun `a broken account doesn't stop the others from syncing`() = scenario {
        // Arrange
        val brokenUser = server.user()
        val workingUser = server.user()
        client.account(brokenUser, password = "wrong-password")
        val working = client.account(workingUser)
        server.deliver(workingUser) { inbox { message(SUBJECT) } }

        // Act
        driver.syncAllAccounts()

        // Assert
        assertThat(driver.subjects(working)).contains(SUBJECT)
    }

    private companion object {
        const val SUBJECT = "Still arrives"
    }
}
