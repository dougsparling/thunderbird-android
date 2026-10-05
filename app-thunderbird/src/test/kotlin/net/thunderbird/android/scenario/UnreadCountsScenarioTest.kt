package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ScenarioDriver
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/** The unread counts in the folder list follow the user's actions and changes made on another device. */
class UnreadCountsScenarioTest : ScenarioTest() {

    @Test
    fun `unread counts follow changes`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message("One")
                message("Two")
                message("Three")
                message("Four")
            }
            folder(WORK.segments.single())
        }
        val account = client.account(user)
        assertThat(driver.unreadCount(account, FolderPath.INBOX)).isEqualTo(4)

        // Act
        driver.markRead(account, FolderPath.INBOX, "One")
        driver.delete(account, FolderPath.INBOX, "Two")
        driver.move(account, FolderPath.INBOX, "Three", to = WORK)

        // Assert
        assertThat(driver.unreadCount(account, FolderPath.INBOX)).isEqualTo(1)
        assertThat(driver.unreadCount(account, WORK)).isEqualTo(1)

        // Act
        server.setFlags(user, FolderPath.INBOX, "Four", add = setOf(SystemFlag.SEEN))
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.unreadCount(account, FolderPath.INBOX)).isEqualTo(0)
    }

    private fun ScenarioDriver.unreadCount(account: ClientAccount, folder: FolderPath): Int =
        folderList(account).single { it.path == folder }.unreadCount

    private companion object {
        val WORK = FolderPath.of("Work")
    }
}
