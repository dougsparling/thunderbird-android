package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.proxy.Direction

/**
 * The inbox holds one message whose body is larger than the account's 128 KB auto-download limit, next to three small
 * ones. The connection drops once part-way through downloading that large body, so the sync that was fetching it
 * fails. The user pulls to refresh twice and must end up seeing all four messages, so an interrupted download of a
 * large message never hides mail or wedges the folder.
 *
 * The fault rule is armed before the account is added, so it interrupts the first time the app tries to download the
 * large body, whether that is the initial mail check during account setup or the first pull to refresh. The second
 * pull is what completes the download once the one-shot rule has been used up.
 */
class LargeMessageInterruptedScenarioTest : ScenarioTest() {

    @Test
    fun `an interrupted large message download still ends with all messages listed`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(LARGE_SUBJECT)
                    from(SENDER)
                    text("x".repeat(LARGE_BODY_CHARS))
                }
                message {
                    subject(SMALL_SUBJECT_1)
                    from(SENDER)
                    text("Small message one.")
                }
                message {
                    subject(SMALL_SUBJECT_2)
                    from(SENDER)
                    text("Small message two.")
                }
                message {
                    subject(SMALL_SUBJECT_3)
                    from(SENDER)
                    text("Small message three.")
                }
            }
        }

        // Drop the connection once the server has sent at least 128 KB downstream; the first large body to be
        // downloaded crosses that mark part-way through.
        network {
            afterBytes(INTERRUPT_AFTER_BYTES, Direction.DOWNSTREAM) { disconnect() }.once()
        }

        val account = client.account(user)

        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Guards against a vacuous pass if the app stops downloading the large body and the rule never fires.
        assertThat(proxy.transcript()).contains(
            "!! disconnect (rule: afterBytes $INTERRUPT_AFTER_BYTES DOWNSTREAM)",
        )
        assertThat(driver.messageList(account, FolderPath.INBOX).map(ClientMessage::subject))
            .containsExactlyInAnyOrder(LARGE_SUBJECT, SMALL_SUBJECT_1, SMALL_SUBJECT_2, SMALL_SUBJECT_3)
    }

    private companion object {
        const val SENDER = "alice@example.org"
        const val LARGE_SUBJECT = "Large message with a big body"
        const val SMALL_SUBJECT_1 = "Small message one"
        const val SMALL_SUBJECT_2 = "Small message two"
        const val SMALL_SUBJECT_3 = "Small message three"

        // Comfortably more than the account's 128 KB auto-download limit, so part of it is always still in flight
        // when the 128 KB mark is crossed.
        const val LARGE_BODY_CHARS = 400_000
        const val INTERRUPT_AFTER_BYTES = 128L * 1024
    }
}
