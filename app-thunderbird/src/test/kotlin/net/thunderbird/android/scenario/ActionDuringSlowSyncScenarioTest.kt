package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * While one account's refresh is slow, the user marks a message of another account as read. Both finish: the refresh
 * brings the new mail and the change reaches the other server.
 */
class ActionDuringSlowSyncScenarioTest : ScenarioTest() {

    @Test
    fun `an action during another account's slow refresh still completes`() = scenario {
        // Arrange
        val slowUser = server.user()
        val otherUser = server.user { inbox { message(OTHER_SUBJECT) } }
        val slow = client.account(slowUser)
        val other = client.account(otherUser)
        server.deliver(slowUser) { inbox { message(SLOW_SUBJECT) } }
        network { imap.onCommand("UID FETCH").beforeServerSees { delay(3.seconds) }.once() }

        // Act
        driver.startPullToRefresh(slow, FolderPath.INBOX)
        driver.markRead(other, FolderPath.INBOX, OTHER_SUBJECT)
        driver.awaitIdle()

        // Assert
        assertThat(driver.subjects(slow)).contains(SLOW_SUBJECT)
        val flags = server.stateOf(otherUser).folder(FolderPath.INBOX).message(OTHER_SUBJECT).flags
        assertThat(flags).contains(SystemFlag.SEEN)
    }

    private companion object {
        const val SLOW_SUBJECT = "Takes a while"
        const val OTHER_SUBJECT = "Quick one"
    }
}
