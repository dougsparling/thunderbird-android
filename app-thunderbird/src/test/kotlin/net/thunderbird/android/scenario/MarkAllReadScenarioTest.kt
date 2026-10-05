package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.each
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * "Mark all as read" in INBOX marks every message the user can see read, in the app and on the server.
 *
 * INBOX starts with three unread messages. The user picks "Mark all as read"; all three must then carry \Seen on the
 * server and show as read in the app's message list.
 *
 * The variant where a message arrives on the server after the last sync is not asserted here: "mark all as read" only
 * acts on the messages the app has synced, and the timing of a just-delivered message is left to a separate scenario.
 */
class MarkAllReadScenarioTest : ScenarioTest() {

    @Test
    fun `mark all read marks every inbox message read on the server and in the app`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message("Unread one")
                message("Unread two")
                message("Unread three")
            }
        }
        val account = client.account(user)
        assertThat(driver.messageList(account, FolderPath.INBOX).map(ClientMessage::isRead))
            .containsExactly(false, false, false)

        // Act
        driver.markAllRead(account, FolderPath.INBOX)

        // Assert
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).messages.map { it.flags })
            .each { it.contains(SystemFlag.SEEN) }
        assertThat(driver.messageList(account, FolderPath.INBOX).map(ClientMessage::isRead))
            .containsExactly(true, true, true)
    }
}
