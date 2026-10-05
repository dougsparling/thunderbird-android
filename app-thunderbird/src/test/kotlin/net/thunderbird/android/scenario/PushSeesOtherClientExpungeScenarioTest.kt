package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Push is listening for new mail. Another client deletes and expunges a message without going through this app. The
 * app must notice on its own, without a pull to refresh, and stop showing the message.
 */
class PushSeesOtherClientExpungeScenarioTest : ScenarioTest() {

    @Test
    fun `push shows another client's expunge`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(KEPT_SUBJECT)
                message(EXPUNGED_SUBJECT)
            }
        }
        val account = client.account(user)
        driver.enablePush(account, FolderPath.INBOX)
        awaitAppListening()

        // Act
        server.deleteMessage(user, FolderPath.INBOX, EXPUNGED_SUBJECT)

        // Assert
        eventually {
            assertThat(driver.subjects(account)).containsExactly(KEPT_SUBJECT)
        }
    }

    private companion object {
        const val KEPT_SUBJECT = "Kept by another client"
        const val EXPUNGED_SUBJECT = "Expunged on another client"
    }
}
