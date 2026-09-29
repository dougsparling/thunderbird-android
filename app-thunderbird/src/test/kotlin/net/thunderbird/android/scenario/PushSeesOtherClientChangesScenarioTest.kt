package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioScope
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * Push is listening for new mail. Another client changes the mailbox without going through this app: it stars message
 * A and expunges message B. The app must notice on its own, without a pull to refresh, and show A starred and B gone.
 */
class PushSeesOtherClientChangesScenarioTest : ScenarioTest() {

    @Test
    fun `push shows another client's star and expunge`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(STARRED_SUBJECT)
                    from(SENDER)
                    text("Another client stars this.")
                }
                message {
                    subject(EXPUNGED_SUBJECT)
                    from(SENDER)
                    text("Another client expunges this.")
                }
            }
        }
        val account = client.account(user)
        driver.enablePush(account, FolderPath.INBOX)
        awaitAppListening()

        // Guards against a vacuous pass: both messages must be visible before the other client changes them.
        assertThat(inboxSubjects(account)).containsExactly(EXPUNGED_SUBJECT, STARRED_SUBJECT)

        // Another client stars A on the server, never through this app. Push must carry the change.
        server.setFlags(user, FolderPath.INBOX, STARRED_SUBJECT, add = setOf(SystemFlag.FLAGGED))
        eventually {
            assertThat(message(account, STARRED_SUBJECT)).prop(ClientMessage::isStarred).isTrue()
        }

        // Wait until the app is listening again; only then can the server's expunge notification reach it.
        awaitAppListening()
        server.deleteMessage(user, FolderPath.INBOX, EXPUNGED_SUBJECT)
        eventually {
            assertThat(inboxSubjects(account)).doesNotContain(EXPUNGED_SUBJECT)
        }
    }

    private fun ScenarioScope.inboxSubjects(account: ClientAccount): List<String?> =
        driver.messageList(account, FolderPath.INBOX).map(ClientMessage::subject)

    private fun ScenarioScope.message(account: ClientAccount, subject: String): ClientMessage =
        driver.messageList(account, FolderPath.INBOX).single { it.subject == subject }

    private companion object {
        const val SENDER = "frank@example.org"
        const val STARRED_SUBJECT = "Starred on another client"
        const val EXPUNGED_SUBJECT = "Expunged on another client"
    }
}
