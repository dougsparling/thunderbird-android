package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.message
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * Flag changes made by another client show up in the app on the next refresh. INBOX has A (unread), B (unstarred)
 * and C (read); another client marks A read, stars B and marks C unread. After the user pulls to refresh the app
 * shows A read, B starred and C unread.
 */
class ServerSideFlagChangesScenarioTest : ScenarioTest() {

    @Test
    fun `flag changes from another client are mirrored on refresh`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(UNREAD_SUBJECT)
                message(UNSTARRED_SUBJECT)
                message(READ_SUBJECT) { flags(SystemFlag.SEEN) }
            }
        }
        val account = client.account(user)
        assertThat(driver.message(account, UNREAD_SUBJECT)).prop(ClientMessage::isRead).isFalse()
        assertThat(driver.message(account, UNSTARRED_SUBJECT)).prop(ClientMessage::isStarred).isFalse()
        assertThat(driver.message(account, READ_SUBJECT)).prop(ClientMessage::isRead).isTrue()

        // Act
        server.setFlags(user, FolderPath.INBOX, UNREAD_SUBJECT, add = setOf(SystemFlag.SEEN))
        server.setFlags(user, FolderPath.INBOX, UNSTARRED_SUBJECT, add = setOf(SystemFlag.FLAGGED))
        server.setFlags(user, FolderPath.INBOX, READ_SUBJECT, remove = setOf(SystemFlag.SEEN))
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.message(account, UNREAD_SUBJECT)).prop(ClientMessage::isRead).isTrue()
        assertThat(driver.message(account, UNSTARRED_SUBJECT)).prop(ClientMessage::isStarred).isTrue()
        assertThat(driver.message(account, READ_SUBJECT)).prop(ClientMessage::isRead).isFalse()
    }

    private companion object {
        const val UNREAD_SUBJECT = "Marked read on another client"
        const val UNSTARRED_SUBJECT = "Starred on another client"
        const val READ_SUBJECT = "Marked unread on another client"
    }
}
