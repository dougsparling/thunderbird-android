package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlin.random.Random
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccountSettings
import net.thunderbird.android.scenario.harness.ClientAttachment
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * An attachment of a message above the automatic download limit isn't downloaded with the message. The message view
 * lists it, and tapping it downloads exactly the bytes the sender attached.
 */
class DownloadAttachmentScenarioTest : ScenarioTest() {

    @Test
    fun `tapping an attachment downloads its content`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(user, settings = ClientAccountSettings(autoDownloadLimitBytes = LIMIT_BYTES))
        server.deliver(user) {
            inbox {
                message(SUBJECT) {
                    mixed {
                        text("See attached.")
                        attachment(FILE_NAME, CONTENT)
                    }
                }
            }
        }
        driver.pullToRefresh(account, FolderPath.INBOX)
        val content = driver.open(account, FolderPath.INBOX, SUBJECT)
        assertThat(content.attachments.map(ClientAttachment::isDownloaded)).containsExactly(false)

        // Act
        val downloaded = driver.downloadAttachment(account, FolderPath.INBOX, SUBJECT, FILE_NAME)

        // Assert
        assertThat(downloaded?.toList()).isEqualTo(CONTENT.toList())
    }

    private companion object {
        const val LIMIT_BYTES = 4096
        const val SUBJECT = "Holiday photos"
        const val FILE_NAME = "beach.bin"
        val CONTENT: ByteArray = Random(seed = 42).nextBytes(20_000)
    }
}
