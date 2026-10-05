package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.random.Random
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccountSettings
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The connection drops while an attachment downloads. The app reports the failure, and tapping the attachment again
 * downloads it completely.
 */
class AttachmentDownloadFailsScenarioTest : ScenarioTest() {

    @Test
    fun `a failed attachment download can be retried`() = scenario {
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
        // The attachment is the message's second body part.
        network {
            imap.onCommand("UID FETCH", label = "attachment part") { args -> "BODY.PEEK[2]" in args }
                .beforeServerSees { disconnect() }
                .once()
        }

        // Act
        val firstTry = driver.downloadAttachment(account, FolderPath.INBOX, SUBJECT, FILE_NAME)

        // Assert
        assertThat(proxy.transcript()).contains("!! disconnect (rule: onCommand UID FETCH")
        assertThat(firstTry).isNull()

        // Act
        val secondTry = driver.downloadAttachment(account, FolderPath.INBOX, SUBJECT, FILE_NAME)

        // Assert
        assertThat(secondTry?.toList()).isEqualTo(CONTENT.toList())
    }

    private companion object {
        const val LIMIT_BYTES = 4096
        const val SUBJECT = "Contract"
        const val FILE_NAME = "contract.bin"
        val CONTENT: ByteArray = Random(seed = 7).nextBytes(20_000)
    }
}
