package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/** Sending a saved draft delivers it and removes the draft, on the server and in the app. */
class SendDraftScenarioTest : ScenarioTest() {

    @Test
    fun `sending a draft delivers it and removes the draft`() = scenario {
        // Arrange
        val sender = server.user()
        val recipient = server.user()
        val account = client.account(sender)
        driver.saveDraft(account, Composition(to = listOf(recipient.username), subject = SUBJECT, text = "Ready now"))

        // Act
        driver.sendDraft(account, SUBJECT)

        // Assert
        // The server delivers to local users asynchronously.
        eventually {
            assertThat(server.stateOf(recipient).folder(FolderPath.INBOX).subjects).containsExactly(SUBJECT)
        }
        assertThat(server.stateOf(sender).folder(DRAFTS).messages).isEmpty()
        assertThat(driver.subjects(account, DRAFTS)).isEmpty()
    }

    private companion object {
        val DRAFTS = FolderPath.of("Drafts")
        const val SUBJECT = "Finally finished"
    }
}
