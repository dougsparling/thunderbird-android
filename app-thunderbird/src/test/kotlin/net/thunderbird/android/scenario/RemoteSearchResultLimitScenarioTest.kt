package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccountSettings
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ClientRemoteSearch
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * With a limit on server search results, the app fetches only that many of the hits at first and offers to load the
 * rest, which "load more results" then fetches.
 */
class RemoteSearchResultLimitScenarioTest : ScenarioTest() {

    @Test
    fun `server search results beyond the limit are loaded on request`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                NEEDLES.forEach { message(it) }
                repeat(FILLER_COUNT) { message("Filler $it") }
            }
        }
        val account = client.account(user, settings = ClientAccountSettings(remoteSearchResultLimit = LIMIT))

        // Act
        val firstPage = driver.searchOnServer(account, FolderPath.INBOX, QUERY)

        // Assert
        assertThat(firstPage.results).hasSize(LIMIT)
        assertThat(firstPage).prop(ClientRemoteSearch::hasMoreResults).isTrue()

        // Act
        val secondPage = driver.loadMoreSearchResults(firstPage)

        // Assert
        assertThat(secondPage.results.map(ClientMessage::subject)).containsExactlyInAnyOrder(*NEEDLES.toTypedArray())
        assertThat(secondPage).prop(ClientRemoteSearch::hasMoreResults).isFalse()
    }

    private companion object {
        const val QUERY = "needle"
        const val LIMIT = 2
        val NEEDLES = listOf("Needle one", "Needle two", "Needle three", "Needle four")
        const val FILLER_COUNT = 30
    }
}
