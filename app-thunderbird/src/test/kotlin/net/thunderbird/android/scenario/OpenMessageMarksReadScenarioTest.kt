package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ClientMessageContent
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.message
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/** Opening an unread message shows it and marks it read, in the app and on the server, without another refresh. */
class OpenMessageMarksReadScenarioTest : ScenarioTest() {

    @Test
    fun `opening a message shows it and marks it read`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) { text(TEXT) } }
        }
        val account = client.account(user)

        // Act
        val content = driver.open(account, FolderPath.INBOX, SUBJECT)

        // Assert
        assertThat(content).prop(ClientMessageContent::text).isEqualTo(TEXT)
        assertThat(content).prop(ClientMessageContent::isComplete).isTrue()
        assertThat(driver.message(account, SUBJECT)).prop(ClientMessage::isRead).isTrue()
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags).contains(SystemFlag.SEEN)
    }

    private companion object {
        const val SUBJECT = "Meeting notes"
        const val TEXT = "We agreed to ship on Tuesday."
    }
}
