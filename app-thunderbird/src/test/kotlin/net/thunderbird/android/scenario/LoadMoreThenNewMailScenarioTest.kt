package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The message list starts out capped at the account's display count (25) and "load more" reveals the rest. Asking for
 * the older messages must not put the cap back: INBOX has 30 messages, the app first shows the newest 25, "load more"
 * brings it to 30, five more messages are then delivered and another pull to refresh shows all 35.
 */
class LoadMoreThenNewMailScenarioTest : ScenarioTest() {

    @Test
    fun `loading older messages then refreshing after new mail keeps the whole list`() = scenario {
        // Arrange
        val user = server.user {
            inbox { oldSubjects.forEach { message(it) } }
        }
        val account = client.account(user)
        assertThat(driver.subjects(account)).hasSize(DISPLAY_COUNT)

        // Act
        driver.loadMore(account, FolderPath.INBOX)
        server.deliver(user) {
            inbox { newSubjects.forEach { message(it) } }
        }
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.subjects(account)).containsExactlyInAnyOrder(*(oldSubjects + newSubjects).toTypedArray())
    }

    private companion object {
        const val DISPLAY_COUNT = 25
        val oldSubjects = (1..30).map { "Message $it" }
        val newSubjects = (1..5).map { "New message $it" }
    }
}
