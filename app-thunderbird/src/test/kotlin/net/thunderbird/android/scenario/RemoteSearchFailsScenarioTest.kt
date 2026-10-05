package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientRemoteSearch
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/** The connection drops during a server search. The app reports the failure and keeps working. */
class RemoteSearchFailsScenarioTest : ScenarioTest() {

    @Test
    fun `a failed server search is reported and the app keeps working`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(OLD) }
        }
        val account = client.account(user)
        network { imap.onCommand("UID SEARCH").beforeServerSees { disconnect() }.once() }

        // Act
        val search = driver.searchOnServer(account, FolderPath.INBOX, "anything")

        // Assert
        assertThat(proxy.transcript()).contains("!! disconnect (rule: onCommand UID SEARCH beforeServerSees)")
        assertThat(search).prop(ClientRemoteSearch::failed).isTrue()

        // Act
        server.deliver(user) { inbox { message(NEW) } }
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.subjects(account, FolderPath.INBOX)).contains(NEW)
    }

    private companion object {
        const val OLD = "Already there"
        const val NEW = "Arrived after the failed search"
    }
}
