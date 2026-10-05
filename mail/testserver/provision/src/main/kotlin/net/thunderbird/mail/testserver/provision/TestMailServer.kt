package net.thunderbird.mail.testserver.provision

import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag
import net.thunderbird.mail.testserver.fixture.UserFixture

/**
 * Where a running test mail server can be reached. Read from `testserver.*` system properties by [fromSystemProperties].
 *
 * - `testserver.kind`: server implementation, e.g. `james`
 * - `testserver.imap`: `host:port` for plaintext IMAP
 * - `testserver.admin`: base URL of the server's admin API, if it has one
 * - `testserver.domain`: mail domain that test users are created in
 * - `testserver.smtp`: `host:port` for plaintext SMTP with AUTH, if the server offers it
 * - `testserver.pop3`: `host:port` for plaintext POP3, if the server offers it
 * - `testserver.imaps-untrusted`: `host:port` for IMAP over TLS with a certificate nobody trusts, if offered
 */
data class TestServerConfig(
    val kind: String,
    val imapHost: String,
    val imapPort: Int,
    val adminUrl: String?,
    val domain: String,
    val smtp: ServerEndpoint? = null,
    val pop3: ServerEndpoint? = null,
    val untrustedImaps: ServerEndpoint? = null,
) {
    companion object {
        fun fromSystemProperties(): TestServerConfig = parseTestServerConfig(System::getProperty)
    }
}

/** A host and port a protocol server listens on. */
data class ServerEndpoint(val host: String, val port: Int)

enum class ServerCapability {
    SPECIAL_USE_CREATE,
    CONDSTORE,
    MOVE,
    IDLE,
    UIDPLUS,
}

/** A user that exists on the server. [username] is the full login name. */
data class ProvisionedUser(
    val username: String,
    val password: String,
)

/**
 * Server-specific operations. Everything else (folders, messages, flags) goes through standard IMAP in [ImapSeeder],
 * so an adapter for a new server stays small.
 */
interface TestMailServer {
    val config: TestServerConfig
    val capabilities: Set<ServerCapability>

    /** Creates a user with a unique name derived from [nameHint], e.g. a test name. */
    fun createUser(nameHint: String, password: String): ProvisionedUser

    fun deleteUser(user: ProvisionedUser)
}

/** Seeds a user's mailbox over IMAP. Works against any compliant server. */
interface ImapSeeder {
    fun seed(user: ProvisionedUser, fixture: UserFixture)
}

/**
 * Changes a user's mailbox over IMAP the way another mail client would, e.g. the user acting on another device. Works
 * against any compliant server.
 *
 * Messages are identified by their (decoded) Subject header, which must be unique within the folder.
 */
interface MailboxEditor {
    /**
     * Flags the message `\Deleted` and, if [expunge] is true, expunges it. Without UIDPLUS expunging also removes
     * every other message in the folder that is flagged `\Deleted`.
     */
    fun deleteMessage(user: ProvisionedUser, folder: FolderPath, subject: String, expunge: Boolean = true)

    /** Moves the message to [to]; with `UID MOVE` if the server supports it, otherwise copy, delete and expunge. */
    fun moveMessage(user: ProvisionedUser, from: FolderPath, subject: String, to: FolderPath)

    /** Adds [add] to and then removes [remove] from the message's flags. */
    fun setFlags(
        user: ProvisionedUser,
        folder: FolderPath,
        subject: String,
        add: Set<SystemFlag> = emptySet(),
        remove: Set<SystemFlag> = emptySet(),
    )

    /** Deletes the folder and its messages. What happens to subfolders is up to the server (RFC 3501 DELETE). */
    fun deleteFolder(user: ProvisionedUser, path: FolderPath)

    /** Renames [from] to [to], including its subfolders. */
    fun renameFolder(user: ProvisionedUser, from: FolderPath, to: FolderPath)
}

/** Reads a user's server-side state over IMAP, independently of the app under test, for assertions. */
interface ServerStateReader {
    fun read(user: ProvisionedUser): ServerState
}

data class ServerState(val folders: List<ServerFolderState>) {
    fun folder(path: FolderPath): ServerFolderState =
        folders.firstOrNull { it.path == path } ?: error("No folder $path on server; have ${folders.map { it.path }}")
}

/**
 * One folder as the server has it. [uidValidity] is the folder's UIDVALIDITY; it changes when the folder is deleted and
 * created again, which tells clients that UIDs they remember are no longer valid. Null if the server didn't report it.
 */
data class ServerFolderState(
    val path: FolderPath,
    val messages: List<ServerMessageState>,
    val uidValidity: Long? = null,
) {
    val subjects: List<String?> get() = messages.map { it.subject }

    fun message(subject: String): ServerMessageState =
        messages.singleOrNull { it.subject == subject }
            ?: error("Expected one message with subject '$subject' in $path")
}

data class ServerMessageState(
    val uid: Long,
    val subject: String?,
    val messageId: String?,
    val flags: Set<SystemFlag>,
    val keywords: Set<String>,
)
