package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SpecialUse

/**
 * Archiving a message must put it in the account's Archive folder and take it out of INBOX: after the archive and a
 * refresh the server has the message in Archive and not in INBOX, and the app shows the same.
 */
class ArchiveScenarioTest : ScenarioTest() {

    @Test
    fun `archiving a message puts it in the archive and removes it from the inbox`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
            folder("Archive", specialUse = SpecialUse.ARCHIVE)
        }
        val account = client.account(user)

        // Act
        driver.archive(account, FolderPath.INBOX, SUBJECT)
        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.pullToRefresh(account, ARCHIVE)

        // Assert
        val state = server.stateOf(user)
        assertThat(state.folder(FolderPath.INBOX).subjects).isEmpty()
        assertThat(state.folder(ARCHIVE).subjects).containsExactly(SUBJECT)
        assertThat(driver.subjects(account)).isEmpty()
        assertThat(driver.subjects(account, ARCHIVE)).containsExactly(SUBJECT)
    }

    private companion object {
        const val SUBJECT = "Old receipt"
        val ARCHIVE = FolderPath.of("Archive")
    }
}
