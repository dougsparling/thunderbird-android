package net.thunderbird.mail.testserver.fixture

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Server-side state for one mail user, built by the fixture DSL.
 *
 * This is plain data. Nothing here talks to a server; the seeder in `:mail:testserver:provision` turns it into
 * protocol commands.
 */
data class UserFixture(
    val password: String,
    val folders: List<FolderFixture>,
)

/**
 * A folder identified by its logical path, independent of the server's hierarchy delimiter.
 *
 * `FolderPath(listOf("Archive", "2024"))` is created as `Archive/2024` or `Archive.2024` depending on the server.
 * The inbox is [FolderPath.INBOX]. Parent folders that aren't declared explicitly are created implicitly.
 */
data class FolderPath(val segments: List<String>) {
    init {
        require(segments.isNotEmpty()) { "Folder path must not be empty" }
        require(segments.none { it.isEmpty() }) { "Folder path segments must not be empty" }
    }

    val isInbox: Boolean get() = segments.size == 1 && segments[0].equals("INBOX", ignoreCase = true)

    override fun toString(): String = segments.joinToString("/")

    companion object {
        val INBOX = FolderPath(listOf("INBOX"))

        fun of(vararg segments: String) = FolderPath(segments.toList())
    }
}

enum class SpecialUse {
    ARCHIVE,
    DRAFTS,
    JUNK,
    SENT,
    TRASH,
}

data class FolderFixture(
    val path: FolderPath,
    val specialUse: SpecialUse?,
    val messages: List<MessageFixture>,
)

enum class SystemFlag {
    SEEN,
    ANSWERED,
    FLAGGED,
    DELETED,
    DRAFT,
}

/**
 * A message as it will be stored on the server.
 *
 * [rfc822] is the exact byte content to append (CRLF line endings). [subject] and [messageId] are metadata for
 * lookups in tests; for raw messages they're whatever the test declared, which may differ from the headers inside a
 * deliberately malformed message.
 */
@OptIn(ExperimentalTime::class)
class MessageFixture(
    val rfc822: ByteArray,
    val subject: String?,
    val messageId: String?,
    val flags: Set<SystemFlag>,
    val keywords: Set<String>,
    val internalDate: Instant,
) {
    override fun toString(): String = "MessageFixture(subject=$subject, messageId=$messageId, flags=$flags)"
}
