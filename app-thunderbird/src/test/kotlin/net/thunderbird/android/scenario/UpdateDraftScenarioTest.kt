package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/** Saving an edited draft replaces the previous version: the server and the app keep only the latest one. */
class UpdateDraftScenarioTest : ScenarioTest() {

    @Test
    fun `saving an edited draft replaces the old version`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(user)
        driver.saveDraft(account, Composition(to = listOf(TO), subject = FIRST_SUBJECT, text = "First go"))

        // Act
        driver.editDraft(
            account,
            FIRST_SUBJECT,
            Composition(to = listOf(TO), subject = SECOND_SUBJECT, text = "Better"),
        )

        // Assert
        assertThat(server.stateOf(user).folder(DRAFTS).subjects).containsExactly(SECOND_SUBJECT)
        assertThat(driver.subjects(account, DRAFTS)).containsExactly(SECOND_SUBJECT)
    }

    private companion object {
        val DRAFTS = FolderPath.of("Drafts")
        const val TO = "friend@example.org"
        const val FIRST_SUBJECT = "Plan"
        const val SECOND_SUBJECT = "Plan, revised"
    }
}
