package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects

/** "Sync all accounts" fetches new mail for every account. */
class SyncAllAccountsScenarioTest : ScenarioTest() {

    @Test
    fun `sync all accounts fetches new mail everywhere`() = scenario {
        // Arrange
        val firstUser = server.user()
        val secondUser = server.user()
        val first = client.account(firstUser)
        val second = client.account(secondUser)
        server.deliver(firstUser) { inbox { message(FIRST_SUBJECT) } }
        server.deliver(secondUser) { inbox { message(SECOND_SUBJECT) } }

        // Act
        driver.syncAllAccounts()

        // Assert
        assertThat(driver.subjects(first)).contains(FIRST_SUBJECT)
        assertThat(driver.subjects(second)).contains(SECOND_SUBJECT)
    }

    private companion object {
        const val FIRST_SUBJECT = "For the work account"
        const val SECOND_SUBJECT = "For the private account"
    }
}
