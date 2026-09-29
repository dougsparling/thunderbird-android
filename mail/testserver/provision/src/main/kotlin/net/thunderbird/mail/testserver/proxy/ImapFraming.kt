package net.thunderbird.mail.testserver.proxy

import java.io.ByteArrayOutputStream

/** A piece of a byte stream, as split by [ImapFramer]. Concatenating [bytes] of all segments restores the stream. */
internal sealed interface FrameSegment {
    val bytes: ByteArray

    /**
     * One line including its terminator (if [complete]).
     *
     * @property continuation True if this line continues a line that was interrupted by a literal or flushed while
     *   incomplete. Continuations are never IMAP commands or tagged responses.
     * @property complete False if the line was flushed before its terminator arrived.
     * @property literalFollows Size of the literal announced at the end of this line (`{n}` or `{n+}`), if any.
     */
    class Line(
        override val bytes: ByteArray,
        val continuation: Boolean,
        val complete: Boolean,
        val literalFollows: Long?,
    ) : FrameSegment {
        /** The line without its terminator, one char per byte. */
        val text: String by lazy { String(bytes, 0, contentLength, Charsets.ISO_8859_1) }

        val contentLength: Int
            get() = when {
                !complete -> bytes.size
                bytes.size >= 2 && bytes[bytes.size - 2] == CR -> bytes.size - 2
                else -> bytes.size - 1
            }

        val hasCrLf: Boolean
            get() = complete && bytes.size >= 2 && bytes[bytes.size - 2] == CR
    }

    /** Part of a literal of [literalSize] bytes, starting at [offset] within the literal. */
    class LiteralData(
        override val bytes: ByteArray,
        val literalSize: Long,
        val offset: Long,
    ) : FrameSegment {
        val isLast: Boolean
            get() = offset + bytes.size == literalSize
    }
}

private const val CR = '\r'.code.toByte()
private const val LF = '\n'.code.toByte()
private const val DEFAULT_MAX_LINE_BYTES = 64 * 1024

/**
 * Splits a byte stream into IMAP lines and literals. It is protocol-tolerant: anything that is not IMAP still comes out
 * as lines (or partial lines when [maxLineBytes] is exceeded), so the concatenated output always equals the input.
 *
 * Not thread-safe; use one instance per direction of a connection.
 */
internal class ImapFramer(private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES) {
    private val line = ByteArrayOutputStream()
    private var nextLineIsContinuation = false
    private var literalSize = 0L
    private var literalRemaining = 0L

    val hasPartialLine: Boolean
        get() = line.size() > 0

    fun feed(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): List<FrameSegment> {
        val segments = mutableListOf<FrameSegment>()
        var position = offset
        val end = offset + length
        while (position < end) {
            position = if (literalRemaining > 0) {
                readLiteral(buffer, position, end, segments)
            } else {
                readLine(buffer, position, end, segments)
            }
        }
        return segments
    }

    /** Emits the bytes of an incomplete line, e.g. a prompt that is not terminated. The rest becomes a continuation. */
    fun flushPartialLine(): FrameSegment.Line? {
        if (line.size() == 0) return null
        val segment = FrameSegment.Line(line.toByteArray(), nextLineIsContinuation, complete = false, null)
        line.reset()
        nextLineIsContinuation = true
        return segment
    }

    private fun readLiteral(buffer: ByteArray, position: Int, end: Int, segments: MutableList<FrameSegment>): Int {
        val count = minOf(literalRemaining, (end - position).toLong()).toInt()
        val offsetInLiteral = literalSize - literalRemaining
        segments +=
            FrameSegment.LiteralData(buffer.copyOfRange(position, position + count), literalSize, offsetInLiteral)
        literalRemaining -= count
        return position + count
    }

    private fun readLine(buffer: ByteArray, position: Int, end: Int, segments: MutableList<FrameSegment>): Int {
        val room = maxLineBytes - line.size()
        val scanEnd = minOf(end, position + room)
        var index = position
        while (index < scanEnd && buffer[index] != LF) index++

        return if (index < scanEnd) {
            line.write(buffer, position, index + 1 - position)
            segments += completeLine()
            index + 1
        } else {
            line.write(buffer, position, scanEnd - position)
            if (line.size() >= maxLineBytes) segments += requireNotNull(flushPartialLine())
            scanEnd
        }
    }

    private fun completeLine(): FrameSegment.Line {
        val bytes = line.toByteArray()
        line.reset()
        val segment = FrameSegment.Line(bytes, nextLineIsContinuation, complete = true, literalFollows = null)
        val literal = ImapSyntax.literalAtEnd(segment.text)
        if (literal != null) {
            literalSize = literal
            literalRemaining = literal
        }
        nextLineIsContinuation = literal != null
        return FrameSegment.Line(bytes, segment.continuation, complete = true, literalFollows = literal)
    }
}

/** Minimal IMAP syntax helpers. Lines are passed without their terminator. */
internal object ImapSyntax {
    private val LITERAL_AT_END = Regex("""\{(\d{1,18})\+?}$""")
    private val TAG = Regex("""[^\s*+(){%"\\\]]+""")
    private val COMMAND_NAME = Regex("""[A-Za-z][A-Za-z0-9._-]*""")
    private val WHITESPACE = Regex("""\s+""")

    /** A parsed client command line. [arguments] is everything after the command name, possibly empty. */
    data class Command(val tag: String, val name: String, val arguments: String)

    fun literalAtEnd(line: String): Long? = LITERAL_AT_END.find(line)?.groupValues?.get(1)?.toLong()

    fun normalizeCommandName(name: String): String = name.trim().split(WHITESPACE).joinToString(" ").uppercase()

    fun parseCommand(line: String): Command? {
        val parts = line.split(' ', limit = 3)
        val tag = parts[0]
        val name = parts.getOrNull(1)?.takeIf { COMMAND_NAME.matches(it) }
        return if (name == null || !TAG.matches(tag)) null else toCommand(tag, name, parts.getOrNull(2).orEmpty())
    }

    private fun toCommand(tag: String, name: String, rest: String): Command {
        val subParts = rest.split(' ', limit = 2)
        val subCommand = subParts[0].takeIf { COMMAND_NAME.matches(it) }
        return if (name.equals("UID", ignoreCase = true) && subCommand != null) {
            Command(tag, "UID ${subCommand.uppercase()}", subParts.getOrNull(1).orEmpty())
        } else {
            Command(tag, name.uppercase(), rest)
        }
    }

    /** The tag of a tagged server response (`a001 OK ...`), or null for untagged and continuation responses. */
    fun responseTag(line: String): String? {
        val tag = line.substringBefore(' ', missingDelimiterValue = "")
        return tag.takeIf { TAG.matches(it) }
    }

    /** True if a tagged response line reports success. */
    fun isOk(line: String): Boolean = line.split(' ', limit = 3).getOrNull(1).equals("OK", ignoreCase = true)
}
