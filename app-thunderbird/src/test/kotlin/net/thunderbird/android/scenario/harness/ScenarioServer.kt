package net.thunderbird.android.scenario.harness

import java.security.SecureRandom
import java.util.HexFormat
import net.thunderbird.mail.testserver.fixture.UserFixture
import net.thunderbird.mail.testserver.fixture.UserFixtureBuilder
import net.thunderbird.mail.testserver.fixture.userFixture
import net.thunderbird.mail.testserver.provision.ImapSeeder
import net.thunderbird.mail.testserver.provision.ProvisionedUser
import net.thunderbird.mail.testserver.provision.ServerState
import net.thunderbird.mail.testserver.provision.ServerStateReader
import net.thunderbird.mail.testserver.provision.TestMailServer
import net.thunderbird.mail.testserver.provision.TestMailServers
import net.thunderbird.mail.testserver.provision.TestServerConfig

/**
 * The shared test mail server as seen by one scenario: it creates the scenario's users, seeds their mailboxes and reads
 * their state back. All of this talks to the server directly, never through the app or the fault proxy.
 */
class ScenarioServer internal constructor(
    val config: TestServerConfig,
    private val nameHint: String,
) {
    private val server: TestMailServer = TestMailServers.fromConfig(config)
    private val seeder: ImapSeeder = TestMailServers.seeder(config)
    private val stateReader: ServerStateReader = TestMailServers.stateReader(config)
    private val users = mutableListOf<ProvisionedUser>()
    private val messageCounts = mutableMapOf<ProvisionedUser, Int>()

    /**
     * Creates a fresh user, unique to this test, and seeds its mailbox from the fixture DSL:
     *
     * ```
     * val user = server.user {
     *     inbox { message { subject("Hello"); from("alice@example.org"); text("Hi") } }
     *     folder("Archive") { folder("2024") { message { subject("Old") } } }
     * }
     * ```
     */
    fun user(password: String = randomPassword(), block: UserFixtureBuilder.() -> Unit = {}): ProvisionedUser {
        val fixture = userFixture(password, block = block)
        val user = server.createUser(nameHint, password)
        users += user
        seed(user, fixture)
        return user
    }

    /**
     * Adds messages (and folders, if new) to an existing [user]'s mailbox, as if they arrived on the server:
     *
     * ```
     * server.deliver(user) { inbox { message { subject("New") } } }
     * ```
     */
    fun deliver(user: ProvisionedUser, block: UserFixtureBuilder.() -> Unit) {
        val firstMessageNumber = messageCounts.getOrDefault(user, 0) + 1
        seed(user, userFixture(user.password, firstMessageNumber, block))
    }

    /** Reads [user]'s current folders, messages and flags from the server. */
    fun stateOf(user: ProvisionedUser): ServerState = stateReader.read(user)

    private fun seed(user: ProvisionedUser, fixture: UserFixture) {
        seeder.seed(user, fixture)
        messageCounts[user] = messageCounts.getOrDefault(user, 0) + fixture.folders.sumOf { it.messages.size }
    }

    internal fun deleteUsers() {
        val failures = users.mapNotNull { user ->
            runCatching { server.deleteUser(user) }.exceptionOrNull()
        }
        users.clear()
        failures.firstOrNull()?.let { throw it }
    }

    private companion object {
        private const val PASSWORD_BYTES = 12
        private val random = SecureRandom()

        fun randomPassword(): String = HexFormat.of().formatHex(ByteArray(PASSWORD_BYTES).also(random::nextBytes))
    }
}
