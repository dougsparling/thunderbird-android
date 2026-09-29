package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioScope
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Push is listening for new mail when the device loses its network. Mail arrives while the device is offline. When the
 * device comes back online, push must reconnect on its own, fetch the missed mail without a pull to refresh, and go
 * back to listening for new mail.
 */
class PushAcrossOfflineScenarioTest : ScenarioTest() {

    @Test
    fun `pushed mail arrives after the device comes back online`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(FIRST_SUBJECT)
                    from(SENDER)
                    text("Already waiting when the account is added.")
                }
            }
        }
        val account = client.account(user)
        driver.enablePush(account, FolderPath.INBOX)
        awaitAppListening()

        // The device loses its network: the app is told it's offline and its connections are dropped by the proxy.
        goOffline()
        // Guards against a vacuous pass if the network was never actually cut.
        assertThat(proxy.transcript()).contains("!! reset (disconnectAll)")

        server.deliver(user) {
            inbox {
                message {
                    subject(SECOND_SUBJECT)
                    from(SENDER)
                    text("Delivered while the device was offline.")
                }
            }
        }

        goOnline()

        // Push reconnects as soon as the network returns, without waiting for a retry timer: no time passes.
        eventually {
            assertThat(inboxSubjects(account)).containsExactly(SECOND_SUBJECT, FIRST_SUBJECT)
        }
        awaitAppListening()
    }

    private fun ScenarioScope.inboxSubjects(account: ClientAccount): List<String?> = driver.messageList(
        account,
        FolderPath.INBOX,
    ).map(ClientMessage::subject)

    private companion object {
        const val SENDER = "grace@example.org"
        const val FIRST_SUBJECT = "Already there"
        const val SECOND_SUBJECT = "Delivered while offline"
    }
}
