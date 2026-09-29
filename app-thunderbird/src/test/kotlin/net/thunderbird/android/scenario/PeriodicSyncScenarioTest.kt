package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

class PeriodicSyncScenarioTest : ScenarioTest() {

    @Test
    fun `periodic sync picks up mail that arrived since the last run`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(FIRST_SUBJECT)
                    from(SENDER)
                    text("Already waiting when the account is added.")
                }
            }
        }
        val account = client.account(user, checkIntervalMinutes = CHECK_INTERVAL_MINUTES)

        // With a check interval the app doesn't sync right after setup; it waits for the periodic job.
        assertThat(driver.messageList(account, FolderPath.INBOX)).isEmpty()

        driver.periodicSyncDue()

        assertThat(driver.messageList(account, FolderPath.INBOX).map { it.subject }).containsExactly(FIRST_SUBJECT)

        server.deliver(user) {
            inbox {
                message {
                    subject(SECOND_SUBJECT)
                    from(SENDER)
                    text("Arrived between two periodic syncs.")
                }
            }
        }
        driver.periodicSyncDue()

        assertThat(driver.messageList(account, FolderPath.INBOX).map(ClientMessage::subject)).containsExactly(
            SECOND_SUBJECT,
            FIRST_SUBJECT,
        )
    }

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val SENDER = "carol@example.org"
        const val FIRST_SUBJECT = "Already there"
        const val SECOND_SUBJECT = "Just arrived"
    }
}
