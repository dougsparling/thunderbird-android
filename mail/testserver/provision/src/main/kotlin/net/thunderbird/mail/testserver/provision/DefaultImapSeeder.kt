package net.thunderbird.mail.testserver.provision

import java.io.IOException
import net.thunderbird.mail.testserver.fixture.FolderFixture
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.MessageFixture
import net.thunderbird.mail.testserver.fixture.UserFixture

/** What [DefaultImapSeeder] does when a fixture folder has a special use but the server can't set it on CREATE. */
enum class SpecialUseFallback {
    /** Create the folder without the special-use attribute. Tests that rely on it should require the capability. */
    SKIP,

    /** Fail seeding with an [IllegalStateException]. */
    FAIL,
}

/**
 * Seeds a mailbox with standard IMAP commands only, so it works against any compliant server.
 *
 * - Logs in as the user and asks the server for its hierarchy delimiter (`LIST "" ""`).
 * - Creates every declared folder in declaration order. Missing parents are created first; a parent that is itself
 *   declared later is created with its own special use. INBOX and folders that already exist are left alone, including
 *   their special use.
 * - Special use is set with RFC 6154 `CREATE name (USE (\Archive))` when the server advertises CREATE-SPECIAL-USE.
 *   Otherwise [specialUseFallback] decides; tests that depend on special use should require
 *   [ServerCapability.SPECIAL_USE_CREATE].
 * - Appends messages folder by folder, in declaration order, with their flags, keywords and internal date, so UIDs
 *   increase in declaration order.
 */
class DefaultImapSeeder(
    private val host: String,
    private val port: Int,
    private val timeouts: ImapTimeouts = ImapTimeouts(),
    private val specialUseFallback: SpecialUseFallback = SpecialUseFallback.SKIP,
) : ImapSeeder {

    override fun seed(user: ProvisionedUser, fixture: UserFixture) {
        openLoggedInSession(host, port, timeouts, user).use { session ->
            val delimiter = session.hierarchyDelimiter()
            val existing = session.list().mapTo(mutableSetOf()) { it.name }
            val declared = fixture.folders.associateBy { it.path }
            val canCreateSpecialUse = CREATE_SPECIAL_USE in session.capabilities

            for (folder in fixture.folders) {
                val missing = folder.path.selfAndAncestors()
                    .filter { !it.isInbox && it.toServerName(delimiter) !in existing }
                for (path in missing) {
                    val name = path.toServerName(delimiter)
                    session.create(name, specialUseFor(declared[path], canCreateSpecialUse))
                    existing += name
                }
            }

            for (folder in fixture.folders) {
                val name = folder.path.toServerName(delimiter)
                folder.messages.forEach { message -> session.appendMessage(name, message) }
            }
        }
    }

    private fun specialUseFor(folder: FolderFixture?, canCreateSpecialUse: Boolean): String? {
        val specialUse = folder?.specialUse ?: return null
        check(canCreateSpecialUse || specialUseFallback == SpecialUseFallback.SKIP) {
            "Server does not support CREATE-SPECIAL-USE, so folder ${folder.path} can't be marked " +
                "${specialUse.imapAttribute}. Require ServerCapability.SPECIAL_USE_CREATE for this test."
        }
        return if (canCreateSpecialUse) specialUse.imapAttribute else null
    }

    /** `a/b/c` gives `a`, `a/b`, `a/b/c`: parents first. */
    private fun FolderPath.selfAndAncestors(): List<FolderPath> =
        (1..segments.size).map { depth -> FolderPath(segments.take(depth)) }

    private fun ImapSession.appendMessage(folderName: String, message: MessageFixture) {
        val flags = message.flags.map { it.imapName } + message.keywords.map(::requireValidKeyword)
        append(folderName, flags, formatInternalDate(message.internalDate), message.rfc822)
    }

    private companion object {
        const val CREATE_SPECIAL_USE = "CREATE-SPECIAL-USE"
    }
}

internal fun openLoggedInSession(host: String, port: Int, timeouts: ImapTimeouts, user: ProvisionedUser): ImapSession {
    val session = ImapSession(ImapConnection.connect(host, port, timeouts))
    try {
        session.login(user.username, user.password)
    } catch (e: IOException) {
        session.close()
        throw e
    }
    return session
}
