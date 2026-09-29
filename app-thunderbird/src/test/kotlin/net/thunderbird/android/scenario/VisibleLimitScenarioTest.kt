package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The display count (25 by default) limits how much of a folder the app shows after a pull to refresh: INBOX has 40
 * messages with increasing dates, so the app lists exactly the newest 25 and none of the oldest 15. The cap is about
 * what the user is shown, independent of the sync core, so a rewrite must keep it.
 */
class VisibleLimitScenarioTest : ScenarioTest() {

    @Test
    fun `pull to refresh shows only the newest messages up to the display count`() = scenario {
        val user = server.user {
            inbox {
                // Message N gets date BASE_DATE + (N - 1) minutes, so message 40 is the newest.
                repeat(MESSAGE_COUNT) { index ->
                    message {
                        subject("Message ${index + 1}")
                        from(SENDER)
                        text("Body of message ${index + 1}.")
                    }
                }
            }
        }
        val account = client.account(user)

        driver.pullToRefresh(account, FolderPath.INBOX)

        val subjects = driver.messageList(account, FolderPath.INBOX).map { it.subject }
        assertThat(subjects).hasSize(DISPLAY_COUNT)
        assertThat(subjects).containsExactly(*newestSubjects.toTypedArray())
    }

    private companion object {
        const val DISPLAY_COUNT = 25
        const val MESSAGE_COUNT = 40
        const val SENDER = "bob@example.org"

        // The app sorts by date, newest first, so these are messages 40 down to 16.
        val newestSubjects = (MESSAGE_COUNT - DISPLAY_COUNT + 1..MESSAGE_COUNT).map { "Message $it" }.reversed()
    }
}
