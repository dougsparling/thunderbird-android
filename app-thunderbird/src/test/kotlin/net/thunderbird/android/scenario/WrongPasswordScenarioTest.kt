package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.android.scenario.harness.subjects
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * An account is set up with the wrong password. When the user refreshes the folders, the app must tell them the
 * incoming server rejected the login. Once the user saves the right password, refreshes the folders and pulls to
 * refresh INBOX, the authentication-error notification must be gone and the seeded message must be shown.
 */
class WrongPasswordScenarioTest : ScenarioTest() {

    @Test
    fun `wrong password shows an auth error and fixing it clears the error and syncs inbox`() = scenario {
        // Arrange
        val user = server.user {
            inbox { message(SUBJECT) }
        }
        val account = client.account(user, password = WRONG_PASSWORD)

        // Act
        // The folder list can't be fetched without logging in.
        driver.refreshFolders(account)

        // Assert
        eventually {
            assertThat(device.notifications().map { it.title }).contains(AUTH_ERROR_TITLE)
        }

        // Act
        // INBOX only appears in the folder list once a login succeeds.
        driver.updatePassword(account, user.password)
        driver.refreshFolders(account)
        driver.pullToRefresh(account, FolderPath.INBOX)

        // Assert
        eventually {
            assertThat(device.notifications().map { it.title }).doesNotContain(AUTH_ERROR_TITLE)
        }
        assertThat(driver.subjects(account)).containsExactly(SUBJECT)
    }

    private companion object {
        const val SUBJECT = "Private"
        const val AUTH_ERROR_TITLE = "Authentication failed"
        const val WRONG_PASSWORD = "not-the-right-password"
    }
}
