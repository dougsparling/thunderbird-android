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
 * The user stars a message in one folder while a slow refresh of another folder of the same account runs. Both
 * finish: the new mail arrives and the star reaches the server.
 */
class ActionDuringLongSyncScenarioTest : ScenarioTest() {

    @Test
    fun `an action during a slow refresh of the same account completes`() = scenario {
        // Arrange
        val user = server.user {
            folder(WORK.segments.single()) { message(STARRED) }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, WORK)
        server.deliver(user) { inbox { message(NEW) } }
        network { imap.onCommand("UID FETCH").beforeServerSees { delay(3.seconds) }.once() }

        // Act
        driver.startPullToRefresh(account, FolderPath.INBOX)
        driver.setStarred(account, WORK, STARRED, starred = true)
        driver.awaitIdle()

        // Assert
        assertThat(driver.subjects(account)).contains(NEW)
        assertThat(server.stateOf(user).folder(WORK).message(STARRED).flags).contains(SystemFlag.FLAGGED)
    }

    private companion object {
        val WORK = FolderPath.of("Work")
        const val NEW = "Arrives slowly"
        const val STARRED = "Star me meanwhile"
    }
}
