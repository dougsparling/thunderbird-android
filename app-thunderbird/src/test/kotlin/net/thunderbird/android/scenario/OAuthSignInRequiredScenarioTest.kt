package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ScenarioTest

/**
 * An OAuth account the user isn't signed in to doesn't even try to connect: syncing is skipped and the user is asked
 * to sign in.
 */
class OAuthSignInRequiredScenarioTest : ScenarioTest() {

    @Test
    fun `a signed out OAuth account asks the user to sign in`() = scenario {
        // Arrange
        val user = server.user { inbox { message("Waiting") } }
        client.account(user, oAuthSignedOut = true)

        // Act
        driver.syncAllAccounts()

        // Assert
        // The app posts the notification in the background.
        eventually { assertThat(device.notifications().map { it.title }).contains(AUTH_ERROR_TITLE) }
        assertThat(proxy.transcript()).doesNotContain("connected from client")
    }

    private companion object {
        const val AUTH_ERROR_TITLE = "Authentication failed"
    }
}
