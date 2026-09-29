package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Another mail client creates a nested folder (`Clients/2025`, the server's separator is ".") with a message in it
 * after the account is already synced. When the user refreshes the folder list and then pulls the new folder, the
 * folder must appear in the folder list and its message must be shown.
 */
class NewServerFolderScenarioTest : ScenarioTest() {

    @Test
    fun `folder created on the server shows up after refreshing folders and pulling it`() = scenario {
        val user = server.user {
            inbox()
        }
        val account = client.account(user)

        // The account is synced and doesn't know the folder yet.
        assertThat(driver.folderList(account).map { it.path }).doesNotContain(CLIENTS_2025)

        // Another client creates the nested folder and puts a message in it.
        server.deliver(user) {
            folder("Clients") {
                folder("2025") {
                    message {
                        subject(SUBJECT)
                        from("$SENDER_NAME <$SENDER_ADDRESS>")
                        text("Kick-off notes.")
                    }
                }
            }
        }

        // The user refreshes folders, then opens and pulls the new folder.
        driver.refreshFolders(account)
        driver.pullToRefresh(account, CLIENTS_2025)

        assertThat(driver.folderList(account).map { it.path }).contains(CLIENTS_2025)
        assertThat(driver.messageList(account, CLIENTS_2025)).containsExactly(
            ClientMessage(
                subject = SUBJECT,
                senderAddress = SENDER_ADDRESS,
                senderName = SENDER_NAME,
                isRead = false,
                isStarred = false,
            ),
        )
    }

    private companion object {
        val CLIENTS_2025 = FolderPath.of("Clients", "2025")
        const val SUBJECT = "Kick-off notes"
        const val SENDER_NAME = "Alice Example"
        const val SENDER_ADDRESS = "alice@example.org"
    }
}
