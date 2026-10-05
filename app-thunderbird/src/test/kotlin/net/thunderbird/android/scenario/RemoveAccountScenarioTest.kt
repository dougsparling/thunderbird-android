package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/**
 * Removing an account that still has offline changes waiting drops them: they never reach its server. The other
 * account keeps working, and the removed account's mail is gone from the unified inbox.
 */
class RemoveAccountScenarioTest : ScenarioTest() {

    @Test
    fun `removing an account drops its pending changes`() = scenario {
        // Arrange
        val removedUser = server.user { inbox { message(REMOVED_SUBJECT) } }
        val keptUser = server.user()
        val removed = client.account(removedUser)
        val kept = client.account(keptUser)
        goOffline()
        driver.markRead(removed, FolderPath.INBOX, REMOVED_SUBJECT)

        // Act
        driver.removeAccount(removed)
        goOnline()
        server.deliver(keptUser) { inbox { message(KEPT_SUBJECT) } }
        driver.pullToRefresh(kept, FolderPath.INBOX)

        // Assert
        val flags = server.stateOf(removedUser).folder(FolderPath.INBOX).message(REMOVED_SUBJECT).flags
        assertThat(flags).doesNotContain(SystemFlag.SEEN)
        assertThat(driver.subjects(kept)).contains(KEPT_SUBJECT)
        assertThat(driver.unifiedInbox().filter { it.accountEmail == removed.email }).isEmpty()
    }

    private companion object {
        const val REMOVED_SUBJECT = "From the old job"
        const val KEPT_SUBJECT = "From the new job"
    }
}
