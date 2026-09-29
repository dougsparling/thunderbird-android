package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioScope
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Push is listening for new mail. All connections between the app and the server are dropped, as a flaky network path
 * would, and mail arrives while the app is disconnected. Push must notice the broken connection, reconnect when its
 * retry comes due, fetch the missed mail, and go back to listening, all without the user doing anything.
 */
class PushResumesAfterDisconnectScenarioTest : ScenarioTest() {

    @Test
    fun `push reconnects and fetches mail after the connections drop`() = scenario {
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

        // Every connection between the app and the server is dropped, as a broken network path would.
        proxy.disconnectAll()
        // Guards against a vacuous pass if the app had no connection to drop.
        assertThat(proxy.transcript()).contains("!! disconnect (disconnectAll)")

        server.deliver(user) {
            inbox {
                message {
                    subject(SECOND_SUBJECT)
                    from(SENDER)
                    text("Delivered while the app was disconnected.")
                }
            }
        }

        // Push retries after an I/O error; advance enough time for the retry to fire and the re-sync it triggers.
        eventually {
            device.advanceTime(5.minutes)
            assertThat(inboxSubjects(account)).containsExactly(SECOND_SUBJECT, FIRST_SUBJECT)
        }
        awaitAppListening()
    }

    private fun ScenarioScope.inboxSubjects(account: ClientAccount): List<String?> = driver.messageList(
        account,
        FolderPath.INBOX,
    ).map(ClientMessage::subject)

    private companion object {
        const val SENDER = "erin@example.org"
        const val FIRST_SUBJECT = "Already there"
        const val SECOND_SUBJECT = "Delivered while disconnected"
    }
}
