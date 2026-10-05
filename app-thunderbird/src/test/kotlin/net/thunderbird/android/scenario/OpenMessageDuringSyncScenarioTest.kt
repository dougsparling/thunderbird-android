package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import net.thunderbird.android.scenario.harness.ClientAccountSettings
import net.thunderbird.android.scenario.harness.ClientMessageContent
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/** Downloading the rest of a message while a slow refresh of its folder runs still completes the message. */
class OpenMessageDuringSyncScenarioTest : ScenarioTest() {

    @Test
    fun `downloading a message during a slow refresh completes`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(user, settings = ClientAccountSettings(autoDownloadLimitBytes = LIMIT_BYTES))
        server.deliver(user) { inbox { message(SUBJECT) { text(LONG_TEXT) } } }
        driver.pullToRefresh(account, FolderPath.INBOX)
        server.deliver(user) { inbox { message("Arrives slowly") } }
        network { imap.onCommand("UID FETCH").beforeServerSees { delay(3.seconds) }.once() }

        // Act
        driver.startPullToRefresh(account, FolderPath.INBOX)
        val content = driver.downloadCompleteMessage(account, FolderPath.INBOX, SUBJECT)

        // Assert
        assertThat(content).prop(ClientMessageContent::isComplete).isTrue()
        assertThat(content).prop(ClientMessageContent::text).isNotNull().contains(END_MARKER)
    }

    private companion object {
        const val LIMIT_BYTES = 4096
        const val SUBJECT = "Long read"
        const val END_MARKER = "THE END"
        val LONG_TEXT = "Paragraph. ".repeat(1500) + END_MARKER
    }
}
