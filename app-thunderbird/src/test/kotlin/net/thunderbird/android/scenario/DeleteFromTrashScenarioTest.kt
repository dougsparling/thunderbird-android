package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.android.scenario.harness.subjects

/**
 * Deleting a message that is already in the Trash must remove it for good rather than move it around. After the delete
 * and a Trash refresh, no folder on the server holds the message any more and it is gone from the app's Trash list.
 */
class DeleteFromTrashScenarioTest : ScenarioTest() {

    @Test
    fun `deleting a message from trash removes it from the server and the app`() = scenario {
        // Arrange
        val user = server.user {
            folder("Trash") { message(SUBJECT) }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, TRASH)

        // Act
        driver.delete(account, TRASH, SUBJECT)
        driver.pullToRefresh(account, TRASH)

        // Assert
        // The server lists a message even when it is only flagged \Deleted, so no message anywhere means it was
        // expunged.
        assertThat(server.stateOf(user).folders.flatMap { it.subjects }).doesNotContain(SUBJECT)
        assertThat(driver.subjects(account, TRASH)).isEmpty()
    }

    private companion object {
        const val SUBJECT = "Throw me away for good"
    }
}
