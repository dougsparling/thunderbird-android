package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

/** A saved draft is uploaded to the server's Drafts folder, flagged as a draft, and listed in the app's Drafts. */
class SaveDraftScenarioTest : ScenarioTest() {

    @Test
    fun `a saved draft is uploaded to the drafts folder`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(user)

        // Act
        driver.saveDraft(account, Composition(to = listOf("friend@example.org"), subject = SUBJECT, text = "Hi"))

        // Assert
        val draftOnServer = server.stateOf(user).folder(DRAFTS).message(SUBJECT)
        assertThat(draftOnServer.flags).isEqualTo(setOf(SystemFlag.DRAFT))
        assertThat(driver.subjects(account, DRAFTS)).containsExactly(SUBJECT)
    }

    private companion object {
        val DRAFTS = FolderPath.of("Drafts")
        const val SUBJECT = "Unfinished thoughts"
    }
}
