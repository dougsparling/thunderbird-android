package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Another mail client creates a nested folder (`Clients/2025`, the server's separator is ".") with a message in it
 * after the account is already synced. When the user refreshes the folder list and then pulls the new folder, the
 * folder must appear in the folder list and its message must be shown.
 */
class NewServerFolderScenarioTest : ScenarioTest() {

    @Test
    fun `folder created on the server shows up after refreshing folders and pulling it`() = scenario {
        // Arrange
        val user = server.user { inbox() }
        val account = client.account(user)

        // Act
        server.deliver(user) {
            folder("Clients") {
                folder("2025") { message(SUBJECT) }
            }
        }
        driver.refreshFolders(account)
        driver.pullToRefresh(account, CLIENTS_2025)

        // Assert
        assertThat(driver.folderList(account).map { it.path }).contains(CLIENTS_2025)
        assertThat(driver.subjects(account, CLIENTS_2025)).containsExactly(SUBJECT)
    }

    private companion object {
        val CLIENTS_2025 = FolderPath.of("Clients", "2025")
        const val SUBJECT = "Kick-off notes"
    }
}
