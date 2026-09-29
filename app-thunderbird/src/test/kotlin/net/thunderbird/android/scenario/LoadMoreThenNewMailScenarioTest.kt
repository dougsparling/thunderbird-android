package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.hasSize
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The message list starts out capped at the account's display count (25) and "load more" reveals the rest. Asking for
 * the older messages must not put the cap back: INBOX has 30 messages, the app first shows the newest 25, "load more"
 * brings it to 30, five more messages are then delivered and another pull to refresh shows all 35. The display count
 * must only limit what is fetched, not hide mail the user has already asked to see.
 */
class LoadMoreThenNewMailScenarioTest : ScenarioTest() {

    @Test
    fun `loading older messages then refreshing after new mail keeps the whole list`() = scenario {
        val user = server.user {
            inbox {
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

        // The display count limits the first sync to the newest messages.
        driver.pullToRefresh(account, FolderPath.INBOX)
        assertThat(driver.messageList(account, FolderPath.INBOX)).hasSize(DISPLAY_COUNT)

        // "Load more" fetches the older messages, so the whole mailbox is now in the list.
        driver.loadMore(account, FolderPath.INBOX)
        assertThat(driver.messageList(account, FolderPath.INBOX)).hasSize(MESSAGE_COUNT)

        server.deliver(user) {
            inbox {
                repeat(NEW_MESSAGE_COUNT) { index ->
                    message {
                        subject("New message ${index + 1}")
                        from(SENDER)
                        text("Body of new message ${index + 1}.")
                    }
                }
            }
        }

        // Refreshing picks up the new mail and still shows everything the user loaded before.
        driver.pullToRefresh(account, FolderPath.INBOX)
        assertThat(driver.messageList(account, FolderPath.INBOX)).hasSize(MESSAGE_COUNT + NEW_MESSAGE_COUNT)
    }

    private companion object {
        const val DISPLAY_COUNT = 25
        const val MESSAGE_COUNT = 30
        const val NEW_MESSAGE_COUNT = 5
        const val SENDER = "bob@example.org"
    }
}
