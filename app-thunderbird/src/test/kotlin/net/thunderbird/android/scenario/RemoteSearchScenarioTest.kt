package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ClientMessageContent
import net.thunderbird.android.scenario.harness.ClientRemoteSearch
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * A message too old to be among the messages the app keeps is found by searching on the server; the result can be
 * opened like any other message.
 */
class RemoteSearchScenarioTest : ScenarioTest() {

    @Test
    fun `searching on the server finds a message the app doesn't have`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                // Oldest first: the needle is older than the 25 messages the app shows.
                message(NEEDLE)
                repeat(FILLER_COUNT) { message("Filler $it") }
            }
        }
        val account = client.account(user)
        assertThat(driver.subjects(account, FolderPath.INBOX)).doesNotContain(NEEDLE)

        // Act
        val search = driver.searchOnServer(account, FolderPath.INBOX, QUERY)

        // Assert
        assertThat(search).prop(ClientRemoteSearch::failed).isFalse()
        assertThat(search.results.map(ClientMessage::subject)).containsExactly(NEEDLE)
        val content = driver.open(account, FolderPath.INBOX, NEEDLE)
        assertThat(content).prop(ClientMessageContent::isComplete).isTrue()
    }

    private companion object {
        const val QUERY = "needle"
        const val NEEDLE = "The needle in the haystack"
        const val FILLER_COUNT = 30
    }
}
