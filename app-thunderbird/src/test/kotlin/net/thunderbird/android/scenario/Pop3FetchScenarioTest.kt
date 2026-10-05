package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.MailProtocol
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/** A POP3 account shows the server's mail after setup and fetches new mail on refresh. */
class Pop3FetchScenarioTest : ScenarioTest() {

    @Test
    fun `a POP3 account fetches mail`() = scenario {
        // Arrange
        val user = server.user { inbox { message(FIRST) } }
        val account = client.account(user, protocol = MailProtocol.POP3)
        assertThat(driver.subjects(account)).containsExactly(FIRST)
        server.deliver(user) { inbox { message(SECOND) } }

        // Act
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.subjects(account)).containsExactly(SECOND, FIRST)
    }

    private companion object {
        const val FIRST = "Already waiting"
        const val SECOND = "Just arrived"
    }
}
