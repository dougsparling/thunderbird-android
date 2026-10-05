package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.Composition
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.TRASH
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * A message deleted from the outbox before it was sent isn't sent; it goes to Trash, and once online the app uploads
 * it to the server's Trash.
 */
class DeleteFromOutboxScenarioTest : ScenarioTest() {

    @Test
    fun `a message deleted from the outbox ends up in trash unsent`() = scenario {
        // Arrange
        val sender = server.user()
        val recipient = server.user()
        val account = client.account(sender)
        goOffline()
        driver.send(account, Composition(to = listOf(recipient.username), subject = SUBJECT, text = "Hi"))

        // Act
        driver.deleteFromOutbox(account, SUBJECT)
        goOnline()
        driver.pullToRefresh(account, TRASH)

        // Assert
        assertThat(driver.outbox(account)).isEmpty()
        assertThat(server.stateOf(sender).folder(TRASH).subjects).containsExactly(SUBJECT)
        assertThat(driver.subjects(account, TRASH)).containsExactly(SUBJECT)
        assertThat(smtpProxy.transcript()).doesNotContain("C: DATA")
        assertThat(server.stateOf(recipient).folder(FolderPath.INBOX).messages).isEmpty()
    }

    private companion object {
        const val SUBJECT = "Changed my mind"
    }
}
