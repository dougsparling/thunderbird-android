package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioScope
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Periodic sync while the device is offline. INBOX has a 15-minute check interval and has been synced once. The device
 * goes offline before the next periodic run is due, and a new message arrives. The app must not try to reach the
 * server while offline, and the overdue sync must run as soon as the device is back online, without waiting for the
 * next interval.
 */
class PeriodicSyncWaitsForNetworkScenarioTest : ScenarioTest() {

    @Test
    fun `periodic sync that came due while offline runs when the device is back online`() = scenario {
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

        // The first periodic sync is due right after setup; INBOX has now been synced once.
        device.advanceTime(1.minutes)
        assertThat(inboxSubjects(account)).containsExactly(FIRST_SUBJECT)

        // The device is offline when the next periodic run comes due.
        goOffline()
        device.advanceTime(CHECK_INTERVAL_MINUTES.minutes)
        // The app waited for the network instead of trying the server.
        assertThat(proxy.transcript()).doesNotContain("refuse (rule: refuseConnections)")

        server.deliver(user) {
            inbox {
                message {
                    subject(SECOND_SUBJECT)
                    from(SENDER)
                    text("Arrived while the device was offline.")
                }
            }
        }

        // Back online, the overdue sync runs right away; no time passes.
        goOnline()
        assertThat(inboxSubjects(account)).containsExactly(SECOND_SUBJECT, FIRST_SUBJECT)
    }

    private fun ScenarioScope.inboxSubjects(account: ClientAccount): List<String?> = driver.messageList(
        account,
        FolderPath.INBOX,
    ).map(ClientMessage::subject)

    private companion object {
        const val CHECK_INTERVAL_MINUTES = 15
        const val SENDER = "frank@example.org"
        const val FIRST_SUBJECT = "Already there"
        const val SECOND_SUBJECT = "Arrived while offline"
    }
}
