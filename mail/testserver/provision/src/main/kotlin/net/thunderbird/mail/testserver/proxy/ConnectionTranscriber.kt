package net.thunderbird.mail.testserver.proxy

import java.io.ByteArrayOutputStream

/**
 * Writes one connection's traffic to a [Transcript]: lines as text, literals abbreviated, credentials redacted.
 *
 * Redaction: the arguments of `LOGIN` and `AUTHENTICATE` (including literals and continuation lines that belong to
 * the command), and every client line sent while an `AUTHENTICATE` exchange is in progress, are never written.
 *
 * Each direction must only be fed from one thread; the two directions may be fed concurrently.
 */
internal class ConnectionTranscriber(
    private val connectionId: Int,
    private val transcript: Transcript,
    private val literalPreviewBytes: Int = DEFAULT_LITERAL_PREVIEW_BYTES,
) {
    @Volatile
    private var authenticateTag: String? = null

    private val client = DirectionState()
    private val server = DirectionState()

    fun line(direction: Direction, line: FrameSegment.Line) {
        val text = when (direction) {
            Direction.UPSTREAM -> clientLine(line)
            Direction.DOWNSTREAM -> serverLine(line)
        }
        record(direction, text)
    }

    fun literal(direction: Direction, data: FrameSegment.LiteralData) {
        val state = state(direction)
        if (data.offset == 0L) {
            state.literalPreview.reset()
            state.literalRedacted = state.redacting
        }
        val room = literalPreviewBytes - state.literalPreview.size()
        if (room > 0) state.literalPreview.write(data.bytes, 0, minOf(room, data.bytes.size))
        if (data.isLast) {
            state.pendingLiteral = null
            record(direction, state.describeLiteral(data.literalSize, received = data.literalSize))
        } else {
            state.pendingLiteral = data.literalSize to (data.offset + data.bytes.size)
        }
    }

    fun raw(direction: Direction, byteCount: Int) {
        record(direction, "[raw $byteCount bytes]")
    }

    /** Informational event, e.g. connect and close. */
    fun event(text: String) = transcript.record(connectionId, "**", text)

    /** Injected fault or error. */
    fun fault(text: String) = transcript.record(connectionId, "!!", text)

    /** Records a literal in [direction] that was cut off by the connection closing. */
    fun finish(direction: Direction) {
        val state = state(direction)
        state.pendingLiteral?.let { (size, received) ->
            state.pendingLiteral = null
            record(direction, state.describeLiteral(size, received))
        }
    }

    private fun clientLine(line: FrameSegment.Line): String = when {
        line.continuation -> if (client.redacting) REDACTED else line.display()

        authenticateTag != null -> {
            client.redacting = true
            "$REDACTED (authentication exchange)"
        }

        else -> clientCommandLine(line)
    }

    private fun clientCommandLine(line: FrameSegment.Line): String {
        val command = ImapSyntax.parseCommand(line.text)
        client.redacting = command?.name == "LOGIN" || command?.name == "AUTHENTICATE"
        return when {
            command == null -> line.display()

            command.name == "LOGIN" -> "${command.tag} LOGIN $REDACTED"

            command.name == "AUTHENTICATE" -> {
                authenticateTag = command.tag
                val mechanism = command.arguments.substringBefore(' ')
                val hasMore = command.arguments.contains(' ') || line.literalFollows != null
                "${command.tag} AUTHENTICATE $mechanism" + if (hasMore) " $REDACTED" else ""
            }

            else -> line.display()
        }
    }

    private fun serverLine(line: FrameSegment.Line): String {
        if (!line.continuation && line.complete) {
            val tag = ImapSyntax.responseTag(line.text)
            if (tag != null && tag == authenticateTag) authenticateTag = null
        }
        return line.display()
    }

    private fun record(direction: Direction, text: String) {
        transcript.record(connectionId, if (direction == Direction.UPSTREAM) "C:" else "S:", text)
    }

    private fun state(direction: Direction) = if (direction == Direction.UPSTREAM) client else server

    private class DirectionState {
        var redacting = false
        var literalRedacted = false
        val literalPreview = ByteArrayOutputStream()

        /** Size and bytes received so far of a literal that has started but not finished. */
        var pendingLiteral: Pair<Long, Long>? = null

        fun describeLiteral(size: Long, received: Long): String {
            val cutOff = if (received < size) ", connection closed after $received bytes" else ""
            if (literalRedacted) return "[literal $size bytes$cutOff] $REDACTED"

            val preview = literalPreview.toByteArray()
            val remaining = received - preview.size
            val more = if (remaining > 0) " ... [+$remaining more bytes]" else ""
            return "[literal $size bytes$cutOff] ${escapeBytes(preview)}$more"
        }
    }

    companion object {
        const val DEFAULT_LITERAL_PREVIEW_BYTES = 200
        const val REDACTED = "[redacted]"
    }
}

private fun FrameSegment.Line.display(): String {
    val text = escapeBytes(bytes, 0, contentLength)
    return when {
        !complete -> "$text [no line terminator yet]"
        !hasCrLf -> "$text [bare LF]"
        else -> text
    }
}
