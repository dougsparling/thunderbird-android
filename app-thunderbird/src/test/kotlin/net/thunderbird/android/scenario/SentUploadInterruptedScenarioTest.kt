package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The server stores the copy of a sent message in Sent, but the connection drops before the app hears back. The app
 * retries the upload and finds the copy that's already there, so Sent ends up with exactly one copy.
 */
class SentUploadInterruptedScenarioTest : ScenarioTest() {

    @Test
    fun `an interrupted upload to Sent doesn't leave a duplicate`() = scenario {
        // Arrange
        val sender = server.user()
        val recipient = server.user()
        val account = client.account(sender)
        network { imap.onCommand("APPEND").afterServerResponds { disconnect() }.once() }

        // Act
        driver.send(account, Composition(to = listOf(recipient.username), subject = SUBJECT, text = "Hi"))
        driver.pullToRefresh(account, SENT)

        // Assert
        // Guards against a vacuous pass if the upload no longer uses APPEND and the rule never fires.
        assertThat(proxy.transcript()).contains("!! disconnect (rule: onCommand APPEND afterServerResponds)")
        assertThat(server.stateOf(sender).folder(SENT).subjects).containsExactly(SUBJECT)
        assertThat(driver.subjects(account, SENT)).containsExactly(SUBJECT)
    }

    private companion object {
        val SENT = FolderPath.of("Sent")
        const val SUBJECT = "Only once"
    }
}
