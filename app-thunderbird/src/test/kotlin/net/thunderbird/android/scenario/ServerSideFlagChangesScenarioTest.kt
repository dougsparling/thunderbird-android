package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isFalse
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
 * Flag changes made by another client show up in the app on the next refresh. INBOX has A (unread), B (unstarred)
 * and C (read); another client marks A read, stars B and marks C unread. After the user pulls to refresh the app
 * shows A read, B starred and C unread.
 */
class ServerSideFlagChangesScenarioTest : ScenarioTest() {

    @Test
    fun `flag changes from another client are mirrored on refresh`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(UNREAD_SUBJECT)
                    from(SENDER)
                    text("Another client marks this read.")
                }
                message {
                    subject(UNSTARRED_SUBJECT)
                    from(SENDER)
                    text("Another client stars this.")
                }
                message {
                    subject(READ_SUBJECT)
                    from(SENDER)
                    text("Another client marks this unread.")
                    flags(SystemFlag.SEEN)
                }
            }
        }
        val account = client.account(user)

        // Another client changes the flags on the server, not through this app.
        server.setFlags(user, FolderPath.INBOX, UNREAD_SUBJECT, add = setOf(SystemFlag.SEEN))
        server.setFlags(user, FolderPath.INBOX, UNSTARRED_SUBJECT, add = setOf(SystemFlag.FLAGGED))
        server.setFlags(user, FolderPath.INBOX, READ_SUBJECT, remove = setOf(SystemFlag.SEEN))

        // Guards against a vacuous pass if a flag change never reached the server.
        val serverInbox = server.stateOf(user).folder(FolderPath.INBOX)
        assertThat(serverInbox.message(UNREAD_SUBJECT).flags).contains(SystemFlag.SEEN)
        assertThat(serverInbox.message(UNSTARRED_SUBJECT).flags).contains(SystemFlag.FLAGGED)
        assertThat(serverInbox.message(READ_SUBJECT).flags).doesNotContain(SystemFlag.SEEN)

        driver.pullToRefresh(account, FolderPath.INBOX)

        assertThat(message(account, UNREAD_SUBJECT)).prop(ClientMessage::isRead).isTrue()
        assertThat(message(account, UNSTARRED_SUBJECT)).prop(ClientMessage::isStarred).isTrue()
        assertThat(message(account, READ_SUBJECT)).prop(ClientMessage::isRead).isFalse()
    }

    private fun ScenarioScope.message(account: ClientAccount, subject: String): ClientMessage =
        driver.messageList(account, FolderPath.INBOX).single { it.subject == subject }

    private companion object {
        const val SENDER = "erin@example.org"
        const val UNREAD_SUBJECT = "Marked read on another client"
        const val UNSTARRED_SUBJECT = "Starred on another client"
        const val READ_SUBJECT = "Marked unread on another client"
    }
}
