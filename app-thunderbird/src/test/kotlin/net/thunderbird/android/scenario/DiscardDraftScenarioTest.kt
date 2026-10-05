package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Discarding a saved draft removes it from Drafts on the server and in the app. Discarding deletes the draft the way
 * deleting any message does, so it lands in Trash (pinned as today's behaviour).
 */
class DiscardDraftScenarioTest : ScenarioTest() {

    @Test
    fun `discarding a draft removes it from drafts`() = scenario {
        // Arrange
        val user = server.user()
        val account = client.account(user)
        driver.saveDraft(account, Composition(to = listOf("friend@example.org"), subject = SUBJECT, text = "Nah"))

        // Act
        driver.discardDraft(account, SUBJECT)

        // Assert
        assertThat(server.stateOf(user).folder(DRAFTS).messages).isEmpty()
        assertThat(driver.subjects(account, DRAFTS)).isEmpty()
        server.stateOf(user).folder(TRASH).message(SUBJECT)
    }

    private companion object {
        val DRAFTS = FolderPath.of("Drafts")
        const val SUBJECT = "Second thoughts"
    }
}
