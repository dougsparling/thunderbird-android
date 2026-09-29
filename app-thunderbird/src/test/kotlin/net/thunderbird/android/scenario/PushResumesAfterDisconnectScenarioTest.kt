package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Push is listening for new mail. All connections between the app and the server are dropped, as a flaky network path
 * would, and mail arrives while the app is disconnected. Push must notice the broken connection, reconnect when its
 * retry comes due, fetch the missed mail, and go back to listening, all without the user doing anything.
 */
class PushResumesAfterDisconnectScenarioTest : ScenarioTest() {

    @Test
    fun `push reconnects and fetches mail after the connections drop`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(FIRST_SUBJECT) }
        }
        val account = client.account(user)
        driver.enablePush(account, FolderPath.INBOX)
        awaitAppListening()

        // Act
        proxy.disconnectAll()
        server.deliver(user) {
            inbox { message(SECOND_SUBJECT) }
        }

        // Assert
        // Push retries 5 minutes after an I/O error. Time passes a minute at a time, and at most MAX_WAIT: well short
        // of the 30-minute IDLE refresh, so only the retry can bring the mail in.
        var waited = Duration.ZERO
        eventually {
            if (waited < MAX_WAIT) {
                device.advanceTime(1.minutes)
                waited += 1.minutes
            }
            assertThat(driver.subjects(account)).containsExactly(SECOND_SUBJECT, FIRST_SUBJECT)
        }
        awaitAppListening()
    }

    private companion object {
        const val FIRST_SUBJECT = "Already there"
        const val SECOND_SUBJECT = "Delivered while disconnected"

        // Twice the retry delay, in case the app only notices the dropped connection after time started passing.
        val MAX_WAIT = 10.minutes
    }
}
