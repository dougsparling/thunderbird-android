package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccountSettings
import net.thunderbird.android.scenario.harness.ClientMessageContent
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * A message larger than the automatic download limit is only partly downloaded. Opening it shows what's there; the
 * message view's "download complete message" fetches the rest.
 */
class OpenLargeMessageScenarioTest : ScenarioTest() {

    @Test
    fun `a large message is downloaded completely on request`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(user, settings = ClientAccountSettings(autoDownloadLimitBytes = LIMIT_BYTES))
        server.deliver(user) {
            inbox { message(SUBJECT) { text(LONG_TEXT) } }
        }
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Act
        val partial = driver.open(account, FolderPath.INBOX, SUBJECT)

        // Assert
        assertThat(partial).prop(ClientMessageContent::isComplete).isFalse()

        // Act
        val complete = driver.downloadCompleteMessage(account, FolderPath.INBOX, SUBJECT)

        // Assert
        assertThat(complete).prop(ClientMessageContent::isComplete).isTrue()
        assertThat(complete).prop(ClientMessageContent::text).isNotNull().contains(END_MARKER)
    }

    private companion object {
        const val LIMIT_BYTES = 4096
        const val SUBJECT = "The whole story"
        const val END_MARKER = "THE END"
        val LONG_TEXT = "Once upon a time. ".repeat(1000) + END_MARKER
    }
}
