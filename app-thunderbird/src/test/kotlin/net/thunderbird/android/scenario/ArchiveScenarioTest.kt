package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SpecialUse

/**
 * Archiving a message must put it in the account's Archive folder and take it out of INBOX: after the archive and a
 * refresh the server has the message in Archive and not in INBOX, and the app shows the same.
 */
class ArchiveScenarioTest : ScenarioTest() {

    @Test
    fun `archiving a message puts it in the archive and removes it from the inbox`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(SUBJECT)
                    from(SENDER)
                    text("Archive me.")
                }
            }
            folder("Archive", specialUse = SpecialUse.ARCHIVE)
        }
        val account = client.account(user)
        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.refreshFolders(account)

        driver.archive(account, FolderPath.INBOX, SUBJECT)

        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.pullToRefresh(account, ARCHIVE)

        val state = server.stateOf(user)
        assertThat(state.folder(FolderPath.INBOX).messages).isEmpty()
        assertThat(state.folder(ARCHIVE).messages.map { it.subject }).containsExactly(SUBJECT)

        assertThat(driver.messageList(account, FolderPath.INBOX)).isEmpty()
        assertThat(driver.messageList(account, ARCHIVE).map(ClientMessage::subject)).containsExactly(SUBJECT)
    }

    private companion object {
        const val SUBJECT = "Old receipt"
        const val SENDER = "frank@example.org"
        val ARCHIVE = FolderPath.of("Archive")
    }
}
