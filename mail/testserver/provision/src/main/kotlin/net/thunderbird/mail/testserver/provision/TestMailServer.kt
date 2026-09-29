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
 */
data class TestServerConfig(
    val kind: String,
    val imapHost: String,
    val imapPort: Int,
    val adminUrl: String?,
    val domain: String,
) {
    companion object {
        fun fromSystemProperties(): TestServerConfig = parseTestServerConfig(System::getProperty)
    }
}

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

/** Reads a user's server-side state over IMAP, independently of the app under test, for assertions. */
interface ServerStateReader {
    fun read(user: ProvisionedUser): ServerState
}

data class ServerState(val folders: List<ServerFolderState>) {
    fun folder(path: FolderPath): ServerFolderState =
        folders.firstOrNull { it.path == path } ?: error("No folder $path on server; have ${folders.map { it.path }}")
}

data class ServerFolderState(
    val path: FolderPath,
    val messages: List<ServerMessageState>,
) {
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
