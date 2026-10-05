package net.thunderbird.android.scenario

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.message
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * A flag change made offline and a flag change made by another client must not clobber each other. INBOX has unread,
 * unstarred A. The user goes offline and marks A read; while the app is offline another client stars A on the server.
 * Once the app is back online, A must end up both read and starred on the server and in the app.
 */
class FlagConflictScenarioTest : ScenarioTest() {

    @Test
    fun `an offline mark-as-read and a server-side star both survive`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
        }
        val account = client.account(user)

        // Act
        goOffline()
        driver.markRead(account, FolderPath.INBOX, SUBJECT)
        server.setFlags(user, FolderPath.INBOX, SUBJECT, add = setOf(SystemFlag.FLAGGED))
        goOnline()
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags).all {
            contains(SystemFlag.SEEN)
            contains(SystemFlag.FLAGGED)
        }
        assertThat(driver.message(account, SUBJECT)).all {
            prop(ClientMessage::isRead).isTrue()
            prop(ClientMessage::isStarred).isTrue()
        }
    }

    private companion object {
        const val SUBJECT = "Quarterly report"
    }
}
