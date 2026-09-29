package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * An unread message in a nested folder (`Archive/2024`) shows up in the app with its subject and sender, unread, once
 * the user pulls that folder. Only looking at it must not mark it read on the server.
 */
class NestedFolderSyncScenarioTest : ScenarioTest() {

    @Test
    fun `unread message in a nested folder shows up unread and stays unread on the server`() = scenario {
        // Arrange
        val user = server.user {
            folder("Archive") {
                folder("2024") {
                    message(SUBJECT) { from("$SENDER_NAME <$SENDER_ADDRESS>") }
                }
            }
        }
        val account = client.account(user)

        // Act
        driver.pullToRefresh(account, ARCHIVE_2024)

        // Assert
        assertThat(driver.folderList(account).map { it.path }).contains(ARCHIVE_2024)
        assertThat(driver.messageList(account, ARCHIVE_2024)).containsExactly(
            ClientMessage(
                subject = SUBJECT,
                senderAddress = SENDER_ADDRESS,
                senderName = SENDER_NAME,
                isRead = false,
                isStarred = false,
            ),
        )
        assertThat(server.stateOf(user).folder(ARCHIVE_2024).message(SUBJECT).flags).doesNotContain(SystemFlag.SEEN)
    }

    private companion object {
        val ARCHIVE_2024 = FolderPath.of("Archive", "2024")
        const val SUBJECT = "Planning meeting minutes"
        const val SENDER_NAME = "Alice Example"
        const val SENDER_ADDRESS = "alice@example.org"
    }
}
