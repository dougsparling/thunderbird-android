package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * A draft saved and then edited while offline reaches the server as one draft, the latest version, once the device
 * is back online and the app syncs.
 */
class SaveDraftOfflineScenarioTest : ScenarioTest() {

    @Test
    fun `drafts saved offline reach the server as one up to date draft`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(user)
        goOffline()

        // Act
        driver.saveDraft(account, Composition(to = listOf(TO), subject = FIRST_SUBJECT, text = "First go"))
        driver.editDraft(account, FIRST_SUBJECT, Composition(to = listOf(TO), subject = SECOND_SUBJECT, text = "Later"))

        // Assert
        assertThat(server.stateOf(user).folder(DRAFTS).messages).isEmpty()
        assertThat(driver.subjects(account, DRAFTS)).containsExactly(SECOND_SUBJECT)

        // Act
        goOnline()
        driver.pullToRefresh(account, DRAFTS)

        // Assert
        assertThat(server.stateOf(user).folder(DRAFTS).subjects).containsExactly(SECOND_SUBJECT)
        assertThat(driver.subjects(account, DRAFTS)).containsExactly(SECOND_SUBJECT)
    }

    private companion object {
        val DRAFTS = FolderPath.of("Drafts")
        const val TO = "friend@example.org"
        const val FIRST_SUBJECT = "Written offline"
        const val SECOND_SUBJECT = "Written offline, edited"
    }
}
