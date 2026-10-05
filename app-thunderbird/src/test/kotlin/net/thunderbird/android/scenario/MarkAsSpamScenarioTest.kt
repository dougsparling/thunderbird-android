package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/** The "Spam" action moves the message to the spam folder, on the server and in the app. */
class MarkAsSpamScenarioTest : ScenarioTest() {

    @Test
    fun `the spam action moves the message to spam`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
        }
        val account = client.account(user)

        // Act
        driver.markAsSpam(account, FolderPath.INBOX, SUBJECT)
        driver.pullToRefresh(account, SPAM)

        // Assert
        val serverState = server.stateOf(user)
        assertThat(serverState.folder(FolderPath.INBOX).messages).isEmpty()
        assertThat(serverState.folder(SPAM).subjects).containsExactly(SUBJECT)
        assertThat(driver.subjects(account, SPAM)).containsExactly(SUBJECT)
    }

    private companion object {
        val SPAM = FolderPath.of("Spam")
        const val SUBJECT = "Cheap watches"
    }
}
