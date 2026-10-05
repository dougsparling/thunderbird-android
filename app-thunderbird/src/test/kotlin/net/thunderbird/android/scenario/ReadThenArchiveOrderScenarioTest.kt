package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.message
import net.thunderbird.android.scenario.harness.subjects
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
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
            folder("Archive", specialUse = SpecialUse.ARCHIVE)
        }
        val account = client.account(user)
        network {
            imap.onCommand("UID STORE").afterServerResponds { disconnect() }.once()
        }

        // Act
        driver.markRead(account, FolderPath.INBOX, SUBJECT)
        driver.archive(account, FolderPath.INBOX, SUBJECT)
        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.pullToRefresh(account, ARCHIVE)

        // Assert
        val transcript = proxy.transcript()
        assertThat(transcript).contains(DROP_MARKER)

        val state = server.stateOf(user)
        assertThat(state.folder(FolderPath.INBOX).subjects).isEmpty()
        assertThat(state.folder(ARCHIVE).message(SUBJECT).flags).contains(SystemFlag.SEEN)
        assertThat(driver.subjects(account)).isEmpty()
        assertThat(driver.message(account, SUBJECT, ARCHIVE)).prop(ClientMessage::isRead).isTrue()

        // The end state alone can't show the read mark survived: archiving marks the message read too. So check the
        // order: after the connection dropped, the app sends the read mark again before it moves (or copies) the
        // message to Archive. Archiving's own read mark goes to Archive after the move and doesn't count.
        val afterDrop = transcript.substringAfter(DROP_MARKER)
        val store = afterDrop.indexOf("UID STORE")
        val move = listOf("UID MOVE", "UID COPY").map { afterDrop.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        assertThat(store).isNotEqualTo(-1)
        assertThat(move).isNotEqualTo(-1)
        assertThat(move).isGreaterThan(store)
    }

    private companion object {
        const val SUBJECT = "Weekly digest"
        val ARCHIVE = FolderPath.of("Archive")
        const val DROP_MARKER = "!! disconnect (rule: onCommand UID STORE afterServerResponds)"
    }
}
