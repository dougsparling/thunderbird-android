package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

class PushScenarioTest : ScenarioTest() {

    @Test
    fun `pushed mail shows up without the user refreshing`() = scenario {
        val user = server.user {
            inbox()
        }
        // No check interval, so neither periodic sync nor the user will fetch the new message; only push can.
        val account = client.account(user)
        driver.enablePush(account, FolderPath.INBOX)
        awaitAppListening()

        server.deliver(user) {
            inbox {
                message {
                    subject(SUBJECT)
                    from(SENDER)
                    text("Delivered while the app was listening.")
                }
            }
        }

        eventually {
            assertThat(driver.messageList(account, FolderPath.INBOX).map(ClientMessage::subject))
                .containsExactly(SUBJECT)
        }
    }

    private companion object {
        const val SENDER = "dave@example.org"
        const val SUBJECT = "Pushed to you"
    }
}
