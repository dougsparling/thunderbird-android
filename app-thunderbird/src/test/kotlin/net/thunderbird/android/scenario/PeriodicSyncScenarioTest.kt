package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioScope
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

class PeriodicSyncScenarioTest : ScenarioTest() {

    @Test
    fun `periodic sync fetches new mail once the check interval has passed`() = scenario {
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

        // The first periodic sync is due right after setup.
        device.advanceTime(1.minutes)
        assertThat(inboxSubjects(account)).containsExactly(FIRST_SUBJECT)

        server.deliver(user) {
            inbox {
                message {
                    subject(SECOND_SUBJECT)
                    from(SENDER)
                    text("Arrived between two periodic syncs.")
                }
            }
        }

        device.advanceTime(5.minutes)
        assertThat(inboxSubjects(account)).containsExactly(FIRST_SUBJECT)

        device.advanceTime(10.minutes)
        assertThat(inboxSubjects(account)).containsExactly(SECOND_SUBJECT, FIRST_SUBJECT)
    }

    private fun ScenarioScope.inboxSubjects(account: ClientAccount): List<String?> = driver.messageList(
        account,
        FolderPath.INBOX,
    ).map(ClientMessage::subject)

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val SENDER = "carol@example.org"
        const val FIRST_SUBJECT = "Already there"
        const val SECOND_SUBJECT = "Just arrived"
    }
}
