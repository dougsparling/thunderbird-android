package net.thunderbird.android.scenario.harness

import java.security.SecureRandom
import java.util.HexFormat
import net.thunderbird.mail.testserver.fixture.FolderBuilder
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag
import net.thunderbird.mail.testserver.fixture.UserFixture
import net.thunderbird.mail.testserver.fixture.UserFixtureBuilder
import net.thunderbird.mail.testserver.fixture.userFixture
import net.thunderbird.mail.testserver.provision.ImapSeeder
import net.thunderbird.mail.testserver.provision.MailboxEditor
import net.thunderbird.mail.testserver.provision.ProvisionedUser
import net.thunderbird.mail.testserver.provision.ServerState
import net.thunderbird.mail.testserver.provision.ServerStateReader
import net.thunderbird.mail.testserver.provision.TestMailServer
import net.thunderbird.mail.testserver.provision.TestMailServers
import net.thunderbird.mail.testserver.provision.TestServerConfig

/** The Trash folder the test server creates for every user; a new account picks it up as its trash folder. */
val TRASH: FolderPath = FolderPath.of("Trash")

/**
 * The shared test mail server as seen by one scenario: it creates the scenario's users, seeds their mailboxes, changes
 * them the way another mail client would (the user on another device, a server-side filter) and reads their state
 * back. All of this talks to the server directly, never through the app or the fault proxy.
 *
 * Messages are identified by subject, which must be unique within the folder.
 */
class ScenarioServer internal constructor(
    val config: TestServerConfig,
    private val nameHint: String,
) {
    private val server: TestMailServer = TestMailServers.fromConfig(config)
    private val seeder: ImapSeeder = TestMailServers.seeder(config)
    private val stateReader: ServerStateReader = TestMailServers.stateReader(config)
    private val editor: MailboxEditor = TestMailServers.mailboxEditor(config)
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

    /**
     * Another client deletes the message: flags it `\Deleted` and, with [expunge], removes it for good. (With
     * `expunge = false` the message stays, flagged, as with clients that leave expunging to the user.)
     */
    fun deleteMessage(user: ProvisionedUser, folder: FolderPath, subject: String, expunge: Boolean = true) {
        editor.deleteMessage(user, folder, subject, expunge)
    }

    /** Another client moves the message from [from] to [to] (IMAP MOVE when the server has it). */
    fun moveMessage(user: ProvisionedUser, from: FolderPath, subject: String, to: FolderPath) {
        editor.moveMessage(user, from, subject, to)
    }

    /** Another client adds [add] to and removes [remove] from the message's flags. */
    fun setFlags(
        user: ProvisionedUser,
        folder: FolderPath,
        subject: String,
        add: Set<SystemFlag> = emptySet(),
        remove: Set<SystemFlag> = emptySet(),
    ) {
        editor.setFlags(user, folder, subject, add, remove)
    }

    /** Another client deletes the folder at [path] with its messages. */
    fun deleteFolder(user: ProvisionedUser, path: FolderPath) {
        editor.deleteFolder(user, path)
    }

    /** Another client renames [from] to [to]. */
    fun renameFolder(user: ProvisionedUser, from: FolderPath, to: FolderPath) {
        editor.renameFolder(user, from, to)
    }

    /**
     * Deletes the folder at [path] and creates it again with the messages declared in [block], e.g.
     * `server.recreateFolder(user, FolderPath.of("Lists")) { message { subject("Fresh") } }`.
     *
     * The folder gets a new UIDVALIDITY (see `ServerFolderState.uidValidity`), so the app must throw away the UIDs it
     * knows for it. Apache James also starts UIDs over at 1 for the new folder, so new messages reuse UIDs that
     * belonged to other messages before.
     */
    fun recreateFolder(user: ProvisionedUser, path: FolderPath, block: FolderBuilder.() -> Unit = {}) {
        require(!path.isInbox) { "INBOX can't be deleted, so it can't be recreated" }
        editor.deleteFolder(user, path)
        deliver(user) { folderAt(path, block) }
    }

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

    /** Declares the folder at [path], nesting `folder()` calls for its parents. */
    private fun UserFixtureBuilder.folderAt(path: FolderPath, block: FolderBuilder.() -> Unit) {
        fun FolderBuilder.nested(remaining: List<String>) {
            if (remaining.isEmpty()) block() else folder(remaining.first()) { nested(remaining.drop(1)) }
        }
        folder(path.segments.first()) { nested(path.segments.drop(1)) }
    }

    private companion object {
        private const val PASSWORD_BYTES = 12
        private val random = SecureRandom()

        fun randomPassword(): String = HexFormat.of().formatHex(ByteArray(PASSWORD_BYTES).also(random::nextBytes))
    }
}
