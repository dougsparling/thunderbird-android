package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientFolder
import net.thunderbird.android.scenario.harness.ScenarioTest

/**
 * Setting up an account whose server presents a certificate the device doesn't trust: the app refuses the connection,
 * so the account gets no folders from the server.
 *
 * Today the user isn't told why. The folder list refresh wraps the certificate problem in another exception, which the
 * check for certificate errors doesn't look into, so no certificate-error notification is shown. Pinned as current
 * behaviour; see the scenario backlog.
 */
class CertificateErrorScenarioTest : ScenarioTest() {

    @Test
    fun `an untrusted server certificate keeps the account from syncing`() = scenario {
        // Arrange
        val user = server.user { inbox { message("Can't be fetched") } }
        val account = client.account(user, untrustedTls = true)

        // Act
        driver.syncAllAccounts()

        // Assert
        assertThat(driver.folderList(account).filterNot(ClientFolder::isLocalOnly)).isEmpty()
        assertThat(driver.folderList(account).map { it.path.toString() }).containsExactly("Outbox")
        assertThat(device.notifications()).isEmpty()
    }
}
