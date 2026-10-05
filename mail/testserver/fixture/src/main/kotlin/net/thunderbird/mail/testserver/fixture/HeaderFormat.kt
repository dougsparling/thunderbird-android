package net.thunderbird.mail.testserver.fixture

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val PRINTABLE_ASCII_MIN = 0x20
private const val VISIBLE_ASCII_MIN = 0x21
private const val PRINTABLE_ASCII_MAX = 0x7E
private const val BYTE_MASK = 0xFF

/** Visible (non-space) US-ASCII, the character range of header names, tokens and IMAP atoms. */
internal fun Char.isVisibleAscii(): Boolean = code in VISIBLE_ASCII_MIN..PRINTABLE_ASCII_MAX

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/**
 * Header field formatting: RFC 2231 parameters and folding. Encoded-words are in [EncodedWords].
 *
 * Returned fields are complete header lines, possibly folded with CRLF, without a trailing CRLF.
 */
internal object HeaderFormat {
    private const val MAX_LINE_LENGTH = 78
    private const val TSPECIALS = "()<>@,;:\\\"/[]?="
    private const val ATTRIBUTE_CHAR_SPECIALS = "!#$&+-.^_`|~"

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US)
        .withZone(ZoneOffset.UTC)

    fun validateName(name: String) {
        require(name.isNotEmpty() && name.all { it.isVisibleAscii() && it != ':' }) { "Invalid header name: $name" }
    }

    fun validateValue(value: String) {
        require('\r' !in value && '\n' !in value) { "Header values must not contain line breaks" }
    }

    /** A header whose value is written as-is (apart from folding). */
    fun field(name: String, value: String): String {
        validateName(name)
        validateValue(value)
        return fold("$name: $value")
    }

    /** An unstructured header (e.g. Subject). Values that aren't plain ASCII are RFC 2047 encoded. */
    fun unstructured(name: String, value: String): String {
        validateValue(value)
        return field(name, EncodedWords.encodeIfNeeded(value))
    }

    fun addressList(name: String, addresses: List<String>): String {
        return field(name, addresses.joinToString(", ") { EncodedWords.formatAddress(it) })
    }

    @OptIn(ExperimentalTime::class)
    fun formatDate(instant: Instant): String {
        return DATE_FORMAT.format(java.time.Instant.ofEpochSecond(instant.epochSeconds))
    }

    /** A `; name=value` parameter. Non-ASCII values use RFC 2231 (`name*=UTF-8''...`). */
    fun parameter(name: String, value: String): String {
        validateValue(value)
        val isAscii = value.all { it.code in PRINTABLE_ASCII_MIN..PRINTABLE_ASCII_MAX }
        return when {
            !isAscii -> "; $name*=UTF-8''${percentEncode(value)}"
            value.isNotEmpty() && value.all { it.isVisibleAscii() && it !in TSPECIALS } -> "; $name=$value"
            else -> "; $name=\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
    }

    /**
     * Folds a header line at whitespace so lines stay within 78 characters where possible. A line without a suitable
     * folding point is left long.
     */
    private fun fold(line: String): String {
        val result = StringBuilder()
        var remaining = line
        while (remaining.length > MAX_LINE_LENGTH) {
            val breakIndex = findBreakIndex(remaining) ?: break
            result.append(remaining, 0, breakIndex).append("\r\n")
            remaining = remaining.substring(breakIndex)
        }
        return result.append(remaining).toString()
    }

    private fun findBreakIndex(line: String): Int? {
        // Never produce a line consisting only of whitespace.
        val firstContent = line.indexOfFirst { it != ' ' && it != '\t' }
        val candidates = (firstContent + 1 until line.length).filter { line[it] == ' ' || line[it] == '\t' }
        return candidates.lastOrNull { it <= MAX_LINE_LENGTH } ?: candidates.firstOrNull()
    }

    private fun percentEncode(value: String): String {
        return value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            val char = (byte.toInt() and BYTE_MASK).toChar()
            if (char.isAsciiLetterOrDigit() || char in ATTRIBUTE_CHAR_SPECIALS) {
                char.toString()
            } else {
                "%%%02X".format(Locale.ROOT, byte.toInt() and BYTE_MASK)
            }
        }
    }
}

/**
 * RFC 2047 encoded-words (UTF-8, "B" encoding) for unstructured values and address display names.
 */
internal object EncodedWords {
    // "=?UTF-8?B?" + 48 base64 characters + "?=" is 60 characters, short enough to fit on a line after most names.
    private const val MAX_ENCODED_WORD_BYTES = 36
    private const val ENCODED_WORD_PREFIX = "=?UTF-8?B?"
    private const val ENCODED_WORD_SUFFIX = "?="
    private const val ATEXT_SPECIALS = "!#$%&'*+-/=?^_`{|}~"

    private val NAME_ADDR = Regex("""^(.*?)\s*<([^<>]*)>$""")
    private val QUOTED_PAIR = Regex("""\\(.)""")

    fun encodeIfNeeded(value: String): String = if (needsEncoding(value)) encode(value) else value

    /**
     * Formats one mailbox. `Name <addr>` gets its display name encoded if it isn't ASCII, or quoted if it contains
     * specials. Anything else is written as given.
     */
    fun formatAddress(address: String): String {
        HeaderFormat.validateValue(address)
        val trimmed = address.trim()
        val match = NAME_ADDR.matchEntire(trimmed) ?: return trimmed
        val (rawName, addrSpec) = match.destructured
        val name = rawName.trim()
        val unquotedName = unquote(name)

        val formattedName = when {
            name.isEmpty() -> ""
            needsEncoding(unquotedName) -> encode(unquotedName) + " "
            name.startsWith('"') || name.all { it.isAtext() || it == ' ' } -> "$name "
            else -> "\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\" "
        }
        return "$formattedName<$addrSpec>"
    }

    private fun unquote(name: String): String {
        return if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) {
            name.substring(1, name.length - 1).replace(QUOTED_PAIR, "$1")
        } else {
            name
        }
    }

    private fun needsEncoding(value: String): Boolean {
        return "=?" in value || value.any { it != '\t' && it.code !in PRINTABLE_ASCII_MIN..PRINTABLE_ASCII_MAX }
    }

    /**
     * Encodes the whole value as space-separated encoded-words. The spaces between adjacent encoded-words are ignored
     * when decoding, so they only serve as folding points. Words never split a character.
     */
    private fun encode(value: String): String {
        val words = mutableListOf<String>()
        val chunk = StringBuilder()
        var chunkBytes = 0

        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val char = String(Character.toChars(codePoint))
            val charBytes = char.toByteArray(Charsets.UTF_8).size
            if (chunkBytes + charBytes > MAX_ENCODED_WORD_BYTES) {
                words += chunk.toString()
                chunk.clear()
                chunkBytes = 0
            }
            chunk.append(char)
            chunkBytes += charBytes
            index += Character.charCount(codePoint)
        }
        if (chunk.isNotEmpty()) words += chunk.toString()

        val encoder = Base64.getEncoder()
        return words.joinToString(" ") { word ->
            ENCODED_WORD_PREFIX + encoder.encodeToString(word.toByteArray(Charsets.UTF_8)) + ENCODED_WORD_SUFFIX
        }
    }

    private fun Char.isAtext(): Boolean = isAsciiLetterOrDigit() || this in ATEXT_SPECIALS
}
