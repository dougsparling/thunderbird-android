package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import assertk.assertions.prop
import assertk.assertions.single
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SpecialUse
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * The user marks a message as read and then archives it, and the connection drops right after the server applied the
 * mark-as-read. The message must still end up in Archive, marked as read, and be gone from INBOX, with the mark-as-read
 * sent to the server before the move.
 */
class ReadThenArchiveOrderScenarioTest : ScenarioTest() {

    @Test
    fun `mark read then archive keeps the order and the read flag`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(SUBJECT)
                    from("bob@example.org")
                    text("Read me, then archive me.")
                }
            }
            folder("Archive", specialUse = SpecialUse.ARCHIVE)
        }
        val account = client.account(user)
        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.refreshFolders(account)

        network {
            imap.onCommand("UID STORE").afterServerResponds { disconnect() }.once()
        }
        driver.markRead(account, FolderPath.INBOX, SUBJECT)
        driver.archive(account, FolderPath.INBOX, SUBJECT)

        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.pullToRefresh(account, ARCHIVE)

        // Guards against a vacuous pass if the rule never fires.
        val transcript = proxy.transcript()
        assertThat(transcript).contains("!! disconnect (rule: onCommand UID STORE afterServerResponds)")

        val state = server.stateOf(user)
        assertThat(state.folder(FolderPath.INBOX).messages).isEmpty()
        assertThat(state.folder(ARCHIVE).message(SUBJECT).flags).contains(SystemFlag.SEEN)

        assertThat(driver.messageList(account, FolderPath.INBOX)).isEmpty()
        assertThat(driver.messageList(account, ARCHIVE)).single().prop(ClientMessage::isRead).isTrue()

        // The read mark goes to the server before the message is moved (or copied) to Archive.
        val store = transcript.indexOf("UID STORE")
        val move = listOf("UID MOVE", "UID COPY").map { transcript.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        assertThat(store).isNotEqualTo(-1)
        assertThat(move).isNotEqualTo(-1)
        assertThat(move).isGreaterThan(store)
    }

    private companion object {
        const val SUBJECT = "Weekly digest"
        val ARCHIVE = FolderPath.of("Archive")
    }
}
