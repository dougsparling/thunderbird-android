package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioScope
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * Another client deletes the folder Work and creates it again with a single new message C. The app has already synced
 * Work with messages A and B. When the user refreshes Work, the app must show exactly C: the recreated folder has a new
 * UIDVALIDITY, so the messages the app knew must be thrown away instead of being matched to the new ones, which restart
 * their UIDs at 1.
 */
class FolderRecreatedScenarioTest : ScenarioTest() {

    @Test
    fun `refresh after the folder is recreated elsewhere shows only the new message`() = scenario {
        val user = server.user {
            folder(WORK_NAME) {
                message {
                    subject(SUBJECT_A)
                    from(SENDER)
                    text("Was in Work before it was recreated.")
                }
                message {
                    subject(SUBJECT_B)
                    from(SENDER)
                    text("Was in Work before it was recreated.")
                }
            }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, WORK)
        assertThat(workSubjects(account)).containsExactlyInAnyOrder(SUBJECT_A, SUBJECT_B)

        // Another client removes Work and creates it again with a single message; the new folder has a new UIDVALIDITY.
        server.recreateFolder(user, WORK) {
            message {
                subject(SUBJECT_C)
                from(SENDER)
                text("The only message in the recreated Work.")
            }
        }

        driver.pullToRefresh(account, WORK)

        assertThat(workSubjects(account)).containsExactly(SUBJECT_C)
        assertThat(server.stateOf(user).folder(WORK).messages.map { it.subject }).containsExactly(SUBJECT_C)
    }

    private fun ScenarioScope.workSubjects(account: ClientAccount): List<String?> =
        driver.messageList(account, WORK).map(ClientMessage::subject)

    private companion object {
        const val WORK_NAME = "Work"
        val WORK = FolderPath.of(WORK_NAME)
        const val SENDER = "erin@example.org"
        const val SUBJECT_A = "Report A"
        const val SUBJECT_B = "Report B"
        const val SUBJECT_C = "Report C"
    }
}
