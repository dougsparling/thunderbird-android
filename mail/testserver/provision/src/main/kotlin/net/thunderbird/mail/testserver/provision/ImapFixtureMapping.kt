package net.thunderbird.mail.testserver.provision

import java.time.ZoneOffset
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SpecialUse
import net.thunderbird.mail.testserver.fixture.SystemFlag

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Keywords must be RFC 3501 atoms: printable ASCII without `(`, `)`, `{`, space, `%`, `*`, `"`, `\` or `]`. */
private val KEYWORD_PATTERN = Regex("^[!#$&'+,\\-./0-9:;<=>?@A-Z^_`a-z|}~\\[]+$")

internal val SystemFlag.imapName: String
    get() = when (this) {
        SystemFlag.SEEN -> "\\Seen"
        SystemFlag.ANSWERED -> "\\Answered"
        SystemFlag.FLAGGED -> "\\Flagged"
        SystemFlag.DELETED -> "\\Deleted"
        SystemFlag.DRAFT -> "\\Draft"
    }

/** RFC 6154 attribute, e.g. `\Archive`. */
internal val SpecialUse.imapAttribute: String
    get() = when (this) {
        SpecialUse.ARCHIVE -> "\\Archive"
        SpecialUse.DRAFTS -> "\\Drafts"
        SpecialUse.JUNK -> "\\Junk"
        SpecialUse.SENT -> "\\Sent"
        SpecialUse.TRASH -> "\\Trash"
    }

internal fun systemFlagFromImap(flag: String): SystemFlag? =
    SystemFlag.entries.firstOrNull { it.imapName.equals(flag, ignoreCase = true) }

internal fun requireValidKeyword(keyword: String): String {
    require(KEYWORD_PATTERN.matches(keyword)) {
        "Keyword '$keyword' is not a valid IMAP flag keyword (must be an atom)"
    }
    return keyword
}

/** Formats [instant] as an RFC 3501 `date-time` in UTC, without the surrounding quotes. */
@OptIn(ExperimentalTime::class)
internal fun formatInternalDate(instant: Instant): String {
    val time = java.time.Instant.ofEpochSecond(instant.epochSeconds).atOffset(ZoneOffset.UTC)
    return "%2d-%s-%04d %02d:%02d:%02d +0000".format(
        Locale.ROOT,
        time.dayOfMonth,
        MONTHS[time.monthValue - 1],
        time.year,
        time.hour,
        time.minute,
        time.second,
    )
}

/** Joins [path] into a server mailbox name. INBOX is always `INBOX`. */
internal fun FolderPath.toServerName(delimiter: Char?): String {
    if (isInbox) return "INBOX"
    require(delimiter != null || segments.size == 1) {
        "Server has a flat folder hierarchy (no delimiter) but the fixture declares nested folder $this"
    }
    if (delimiter != null) {
        require(segments.none { delimiter in it }) {
            "Folder $this has a segment containing the server's hierarchy delimiter '$delimiter'"
        }
    }
    return segments.joinToString(delimiter?.toString().orEmpty())
}

/** Splits a server mailbox name into a [FolderPath]. INBOX (any case) becomes [FolderPath.INBOX]. */
internal fun folderPathFromServerName(name: String, delimiter: Char?): FolderPath {
    if (name.equals("INBOX", ignoreCase = true)) return FolderPath.INBOX
    val segments = if (delimiter == null) listOf(name) else name.split(delimiter)
    val normalized = if (segments.first().equals("INBOX", ignoreCase = true)) {
        listOf("INBOX") + segments.drop(1)
    } else {
        segments
    }
    return FolderPath(normalized)
}
