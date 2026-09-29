package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isTrue
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.proxy.Direction

/**
 * The inbox holds one message whose body is larger than the account's 128 KB auto-download limit, next to three small
 * ones. The connection drops once part-way through downloading that large body, during the mail check that runs when
 * the account is added. After one pull to refresh the user must see all four messages, and the app must have
 * downloaded the large body again, so an interrupted download never hides mail or wedges the folder.
 */
class LargeMessageInterruptedScenarioTest : ScenarioTest() {

    @Test
    fun `an interrupted large message download still ends with all messages listed`() = scenario {
        // Arrange
        val user = server.user {
            inbox {
                message(LARGE_SUBJECT) { text("x".repeat(LARGE_BODY_CHARS)) }
                message(SMALL_SUBJECT_1)
                message(SMALL_SUBJECT_2)
                message(SMALL_SUBJECT_3)
            }
        }
        // The large body is the first thing to cross 128 KB on a connection, so the drop hits it part-way through.
        network {
            afterBytes(INTERRUPT_AFTER_BYTES, Direction.DOWNSTREAM) { disconnect() }.once()
        }
        val account = client.account(user)
        assertThat(proxy.transcript()).contains(DROP_MARKER)

        // Act
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        assertThat(driver.subjects(account))
            .containsExactlyInAnyOrder(LARGE_SUBJECT, SMALL_SUBJECT_1, SMALL_SUBJECT_2, SMALL_SUBJECT_3)
        // Being listed only needs the headers. After the drop the server must also have sent the app a large body
        // (whole or partial, as the download limit decides).
        val afterDrop = proxy.transcript().substringAfter(DROP_MARKER)
        val bodySizes = BODY_LITERAL.findAll(afterDrop).map { it.groupValues[1].toLong() }.toList()
        assertThat(bodySizes.any { it >= MIN_LARGE_BODY_BYTES }).isTrue()
    }

    private companion object {
        const val LARGE_SUBJECT = "Large message with a big body"
        const val SMALL_SUBJECT_1 = "Small message one"
        const val SMALL_SUBJECT_2 = "Small message two"
        const val SMALL_SUBJECT_3 = "Small message three"

        // Comfortably more than the account's 128 KB auto-download limit, so part of it is always still in flight
        // when the 128 KB mark is crossed.
        const val LARGE_BODY_CHARS = 400_000
        const val INTERRUPT_AFTER_BYTES = 128L * 1024
        const val DROP_MARKER = "!! disconnect (rule: afterBytes $INTERRUPT_AFTER_BYTES DOWNSTREAM)"

        // Far more than a small message's body, far less than any download limit the app offers.
        const val MIN_LARGE_BODY_BYTES = 32L * 1024

        // A server FETCH response carrying a message body (or part of one) as an IMAP literal of {n} bytes.
        val BODY_LITERAL = Regex("""S: \* \d+ FETCH \(.*(?:BODY|BINARY)\[[^\]]*](?:<\d+>)? \{(\d+)}""")
    }
}
