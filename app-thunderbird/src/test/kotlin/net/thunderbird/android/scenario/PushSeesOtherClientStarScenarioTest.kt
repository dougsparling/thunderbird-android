package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.message
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * Push is listening for new mail. Another client stars a message without going through this app. The app must notice
 * on its own, without a pull to refresh, and show the message starred.
 */
class PushSeesOtherClientStarScenarioTest : ScenarioTest() {

    @Test
    fun `push shows another client's star`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
        }
        val account = client.account(user)
        driver.enablePush(account, FolderPath.INBOX)
        awaitAppListening()

        // Act
        server.setFlags(user, FolderPath.INBOX, SUBJECT, add = setOf(SystemFlag.FLAGGED))

        // Assert
        eventually {
            assertThat(driver.message(account, SUBJECT)).prop(ClientMessage::isStarred).isTrue()
        }
    }

    private companion object {
        const val SUBJECT = "Starred on another client"
    }
}
