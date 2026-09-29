package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.android.scenario.harness.subjects

/**
 * The Trash folder holds three messages, already synced in the app. When the user empties the trash, the messages
 * must be gone for good: not merely flagged `\Deleted` on the server but expunged, and gone from the app's trash list
 * too, with nothing left behind.
 */
class EmptyTrashScenarioTest : ScenarioTest() {

    @Test
    fun `emptying the trash removes every message from the server and the app`() = scenario {
        // Arrange
        val user = server.user {
            folder("Trash") {
                message("Old newsletter")
                message("Expired offer")
                message("Discarded draft")
            }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, TRASH)
        assertThat(driver.subjects(account, TRASH)).hasSize(3)

        // Act
        driver.emptyTrash(account)

        // Assert
        // The server lists a message even when it is only flagged \Deleted, so an empty folder means they were
        // expunged.
        assertThat(server.stateOf(user).folder(TRASH).subjects).isEmpty()
        assertThat(driver.subjects(account, TRASH)).isEmpty()
    }
}
