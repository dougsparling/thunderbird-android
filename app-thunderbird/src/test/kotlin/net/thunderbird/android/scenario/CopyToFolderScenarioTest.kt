package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/** Copying a message leaves it where it was and adds a copy to the destination, on the server and in the app. */
class CopyToFolderScenarioTest : ScenarioTest() {

    @Test
    fun `copying a message keeps the original and adds a copy`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
            folder(WORK.segments.single())
        }
        val account = client.account(user)

        // Act
        driver.copy(account, FolderPath.INBOX, SUBJECT, to = WORK)
        driver.pullToRefresh(account, WORK)

        // Assert
        val serverState = server.stateOf(user)
        assertThat(serverState.folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)
        assertThat(serverState.folder(WORK).subjects).containsExactly(SUBJECT)
        assertThat(driver.subjects(account, FolderPath.INBOX)).containsExactly(SUBJECT)
        assertThat(driver.subjects(account, WORK)).containsExactly(SUBJECT)
    }

    private companion object {
        val WORK = FolderPath.of("Work")
        const val SUBJECT = "Keep a copy"
    }
}
