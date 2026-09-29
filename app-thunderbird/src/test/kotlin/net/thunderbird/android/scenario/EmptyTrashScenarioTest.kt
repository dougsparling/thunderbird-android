package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The Trash folder holds three messages, already synced in the app. When the user empties the trash, the messages
 * must be gone for good: not merely flagged `\Deleted` on the server but expunged, and gone from the app's trash list
 * too, with nothing left behind.
 */
class EmptyTrashScenarioTest : ScenarioTest() {

    @Test
    fun `emptying the trash removes every message from the server and the app`() = scenario {
        val user = server.user {
            folder(TRASH_FOLDER) {
                message {
                    subject(FIRST_SUBJECT)
                    from(SENDER)
                    text("Throw me away.")
                }
                message {
                    subject(SECOND_SUBJECT)
                    from(SENDER)
                    text("Throw me away too.")
                }
                message {
                    subject(THIRD_SUBJECT)
                    from(SENDER)
                    text("And me.")
                }
            }
        }
        val account = client.account(user)

        // Bring the trash folder's messages into the app, so emptying the trash has something to work on.
        driver.pullToRefresh(account, TRASH)
        assertThat(server.stateOf(user).folder(TRASH).messages).hasSize(3)
        assertThat(driver.messageList(account, TRASH)).hasSize(3)

        driver.emptyTrash(account)

        // The server lists a message even when it is only flagged \Deleted,
        // so an empty folder means they were expunged.
        assertThat(server.stateOf(user).folder(TRASH).messages).isEmpty()
        assertThat(driver.messageList(account, TRASH)).isEmpty()
    }

    private companion object {
        const val TRASH_FOLDER = "Trash"
        const val SENDER = "bob@example.org"
        const val FIRST_SUBJECT = "Old newsletter"
        const val SECOND_SUBJECT = "Expired offer"
        const val THIRD_SUBJECT = "Discarded draft"
        val TRASH = FolderPath.of(TRASH_FOLDER)
    }
}
