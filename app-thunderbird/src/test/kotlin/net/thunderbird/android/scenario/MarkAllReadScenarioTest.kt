package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * "Mark all as read" in INBOX marks every message the user can see read, in the app and on the server.
 *
 * INBOX starts with three unread messages, already synced by account setup. The user picks "Mark all as read"; all
 * three must then carry \Seen on the server and show as read in the app's message list.
 *
 * The variant where a message arrives on the server after the last sync is not asserted here: "mark all as read" only
 * acts on the messages the app has synced, and the timing of a just-delivered message is left to a separate scenario.
 */
class MarkAllReadScenarioTest : ScenarioTest() {

    @Test
    fun `mark all read marks every inbox message read on the server and in the app`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(FIRST_SUBJECT)
                    from(SENDER)
                    text("First unread message.")
                }
                message {
                    subject(SECOND_SUBJECT)
                    from(SENDER)
                    text("Second unread message.")
                }
                message {
                    subject(THIRD_SUBJECT)
                    from(SENDER)
                    text("Third unread message.")
                }
            }
        }
        // Account setup runs one mail check, so all three messages are already in the app.
        val account = client.account(user)

        val localBefore = driver.messageList(account, FolderPath.INBOX)
        assertThat(localBefore.map(ClientMessage::subject))
            .containsExactlyInAnyOrder(FIRST_SUBJECT, SECOND_SUBJECT, THIRD_SUBJECT)
        // Guards against a vacuous pass: all three really start unread, locally and on the server.
        assertThat(localBefore.map(ClientMessage::isRead)).containsExactly(false, false, false)
        val serverBefore = server.stateOf(user).folder(FolderPath.INBOX)
        assertThat(serverBefore.message(FIRST_SUBJECT).flags).doesNotContain(SystemFlag.SEEN)
        assertThat(serverBefore.message(SECOND_SUBJECT).flags).doesNotContain(SystemFlag.SEEN)
        assertThat(serverBefore.message(THIRD_SUBJECT).flags).doesNotContain(SystemFlag.SEEN)

        driver.markAllRead(account, FolderPath.INBOX)

        val serverAfter = server.stateOf(user).folder(FolderPath.INBOX)
        assertThat(serverAfter.message(FIRST_SUBJECT).flags).contains(SystemFlag.SEEN)
        assertThat(serverAfter.message(SECOND_SUBJECT).flags).contains(SystemFlag.SEEN)
        assertThat(serverAfter.message(THIRD_SUBJECT).flags).contains(SystemFlag.SEEN)
        assertThat(driver.messageList(account, FolderPath.INBOX).map(ClientMessage::isRead))
            .containsExactly(true, true, true)
    }

    private companion object {
        const val SENDER = "frank@example.org"
        const val FIRST_SUBJECT = "Unread one"
        const val SECOND_SUBJECT = "Unread two"
        const val THIRD_SUBJECT = "Unread three"
    }
}
