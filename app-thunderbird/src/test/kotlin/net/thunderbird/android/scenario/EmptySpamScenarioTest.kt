package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/** "Empty spam" removes every message from the spam folder for good, on the server and in the app. */
class EmptySpamScenarioTest : ScenarioTest() {

    @Test
    fun `empty spam removes all spam for good`() = scenario {
        // Arrange
        val user = server.user {
            folder(SPAM.segments.single()) {
                message("Buy now")
                message("You won")
            }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, SPAM)

        // Act
        driver.emptySpam(account)

        // Assert
        assertThat(server.stateOf(user).folder(SPAM).messages).isEmpty()
        assertThat(driver.subjects(account, SPAM)).isEmpty()
    }

    private companion object {
        val SPAM = FolderPath.of("Spam")
    }
}
