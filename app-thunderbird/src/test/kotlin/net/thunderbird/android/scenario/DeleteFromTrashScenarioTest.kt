package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Deleting a message that is already in the Trash must remove it for good rather than move it around. After the delete
 * and a Trash refresh, no folder on the server holds the message any more and it is gone from the app's Trash list.
 */
class DeleteFromTrashScenarioTest : ScenarioTest() {

    @Test
    fun `deleting a message from trash removes it from the server and the app`() = scenario {
        val user = server.user {
            folder(TRASH_FOLDER) {
                message {
                    subject(SUBJECT)
                    from(SENDER)
                    text("Throw me away for good.")
                }
            }
        }
        val account = client.account(user)

        // Bring the message into the app, so deleting it has something to work on.
        driver.pullToRefresh(account, TRASH)
        assertThat(driver.messageList(account, TRASH).map(ClientMessage::subject)).containsExactly(SUBJECT)

        driver.delete(account, TRASH, SUBJECT)
        driver.pullToRefresh(account, TRASH)

        // The server lists a message even when it is only flagged \Deleted,
        // so no message anywhere means it was expunged.
        assertThat(server.stateOf(user).folders.flatMap { it.messages }.map { it.subject }).doesNotContain(SUBJECT)
        assertThat(driver.messageList(account, TRASH)).isEmpty()
    }

    private companion object {
        const val TRASH_FOLDER = "Trash"
        const val SUBJECT = "Throw me away for good"
        const val SENDER = "bob@example.org"
        val TRASH = FolderPath.of(TRASH_FOLDER)
    }
}
