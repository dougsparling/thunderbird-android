package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioScope
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * Another client changes the inbox on the server: it expunges message A and flags B `\Deleted` without expunging it.
 * When the user pulls to refresh, the app must show only C: the expunged message is gone and the deleted-but-not-
 * expunged message is hidden. The app must not expunge B itself; the server still holds B (flagged `\Deleted`) and C.
 */
class ServerSideDeletionsScenarioTest : ScenarioTest() {

    @Test
    fun `refresh after server-side deletions hides expunged and deleted messages without expunging`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(SUBJECT_A)
                    from(SENDER)
                    text("Expunged by another client.")
                }
                message {
                    subject(SUBJECT_B)
                    from(SENDER)
                    text("Flagged deleted by another client, not expunged.")
                }
                message {
                    subject(SUBJECT_C)
                    from(SENDER)
                    text("Untouched by the other client.")
                }
            }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, FolderPath.INBOX)
        assertThat(inboxSubjects(account)).containsExactly(SUBJECT_C, SUBJECT_B, SUBJECT_A)

        // Another client removes A for good and only flags B \Deleted, as clients that leave expunging to the user do.
        server.deleteMessage(user, FolderPath.INBOX, SUBJECT_A, expunge = true)
        server.deleteMessage(user, FolderPath.INBOX, SUBJECT_B, expunge = false)

        driver.pullToRefresh(account, FolderPath.INBOX)

        // The app hides both the expunged message and the one only flagged \Deleted.
        assertThat(inboxSubjects(account)).containsExactly(SUBJECT_C)

        // The app must not have expunged B: the server still has B (flagged \Deleted) and C, and no A.
        val inbox = server.stateOf(user).folder(FolderPath.INBOX)
        assertThat(inbox.messages.map { it.subject }).containsExactlyInAnyOrder(SUBJECT_B, SUBJECT_C)
        assertThat(inbox.message(SUBJECT_B).flags).contains(SystemFlag.DELETED)
    }

    private fun ScenarioScope.inboxSubjects(account: ClientAccount): List<String?> = driver.messageList(
        account,
        FolderPath.INBOX,
    ).map(ClientMessage::subject)

    private companion object {
        const val SENDER = "bob@example.org"
        const val SUBJECT_A = "Expunged elsewhere"
        const val SUBJECT_B = "Deleted elsewhere"
        const val SUBJECT_C = "Kept"
    }
}
