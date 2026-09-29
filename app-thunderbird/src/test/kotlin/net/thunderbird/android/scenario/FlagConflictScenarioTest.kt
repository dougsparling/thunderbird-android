package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.prop
import assertk.assertions.single
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientAccount
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioScope
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag
import net.thunderbird.mail.testserver.provision.ProvisionedUser

/**
 * A flag change made offline and a flag change made by another client must not clobber each other. INBOX has unread,
 * unstarred A. The user goes offline and marks A read; while the app is offline another client stars A on the server.
 * Once the app is back online, A must end up both read and starred on the server and in the app.
 */
class FlagConflictScenarioTest : ScenarioTest() {

    @Test
    fun `an offline mark-as-read and a server-side star both survive`() = scenario {
        val user = server.user {
            inbox {
                message {
                    subject(SUBJECT)
                    from("bob@example.org")
                    text("Mark me read offline while someone else stars me.")
                }
            }
        }
        val account = client.account(user)
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Given: A is unread and unstarred, both on the server and in the app.
        assertThat(serverFlags(user)).doesNotContain(SystemFlag.SEEN)
        assertThat(message(account)).prop(ClientMessage::isRead).isFalse()
        assertThat(message(account)).prop(ClientMessage::isStarred).isFalse()

        // The user goes offline and marks A read, so the change waits on the device.
        goOffline()
        driver.markRead(account, FolderPath.INBOX, SUBJECT)

        // Another client stars A on the server while this app is offline.
        server.setFlags(user, FolderPath.INBOX, SUBJECT, add = setOf(SystemFlag.FLAGGED))

        // Guards against a vacuous pass if the other client's change never reached the server.
        assertThat(serverFlags(user)).contains(SystemFlag.FLAGGED)
        assertThat(serverFlags(user)).doesNotContain(SystemFlag.SEEN)

        // Back online, the app flushes its pending mark-as-read and picks up the server's star. A second refresh lets
        // any pending command settle before the lists are read.
        goOnline()
        driver.pullToRefresh(account, FolderPath.INBOX)
        driver.pullToRefresh(account, FolderPath.INBOX)

        // On the server both flags are set: neither change clobbered the other.
        assertThat(serverFlags(user)).contains(SystemFlag.SEEN)
        assertThat(serverFlags(user)).contains(SystemFlag.FLAGGED)

        // The app shows A as read and starred.
        assertThat(message(account)).prop(ClientMessage::isRead).isTrue()
        assertThat(message(account)).prop(ClientMessage::isStarred).isTrue()
    }

    private fun ScenarioScope.serverFlags(user: ProvisionedUser): Set<SystemFlag> =
        server.stateOf(user).folder(FolderPath.INBOX).message(SUBJECT).flags

    private fun ScenarioScope.message(account: ClientAccount): ClientMessage =
        driver.messageList(account, FolderPath.INBOX).single()

    private companion object {
        const val SUBJECT = "Quarterly report"
    }
}
