package net.thunderbird.mail.testserver.fixture

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale

/**
 * Renders a message body tree to RFC 822 bytes with CRLF line endings.
 *
 * Boundaries are `=_fixture-<message number>-<n>_`, numbered depth-first; the suffix keeps one boundary from being a
 * prefix of another. The sequence `=_` can't occur in quoted-printable or base64 output, so boundaries never collide
 * with encoded content. Content written unencoded (7bit/8bit) is checked for collisions.
 */
internal class MimeRenderer(private val messageNumber: Int) {
    private var boundaryCounter = 0

    fun renderMessage(headerLines: List<String>, body: MimeBody): ByteArray {
        val entity = render(body)
        return ByteArrayOutputStream().apply {
            (headerLines + entity.headerLines).forEach { writeAscii(it + CRLF) }
            writeAscii(CRLF)
            write(entity.body)
            writeAscii(CRLF)
        }.toByteArray()
    }

    private fun render(body: MimeBody): Entity = when (body) {
        is LeafBody -> renderLeaf(body)
        is MultipartBody -> renderMultipart(body)
    }

    private fun renderLeaf(body: LeafBody): Entity {
        val headerLines = buildList {
            add(HeaderFormat.field("Content-Type", contentType(body.mimeType, body.parameters)))
            add(HeaderFormat.field("Content-Transfer-Encoding", body.encoding.headerValue))
            body.disposition?.let { disposition ->
                val fileName = disposition.fileName?.let { HeaderFormat.parameter("filename", it) }.orEmpty()
                add(HeaderFormat.field("Content-Disposition", disposition.type + fileName))
            }
            body.contentId?.let { add(HeaderFormat.field("Content-ID", it)) }
            body.extraHeaders.forEach { (name, value) -> add(HeaderFormat.unstructured(name, value)) }
        }

        val encoded = when (body.encoding) {
            TransferEncoding.QUOTED_PRINTABLE -> QuotedPrintable.encode(body.content)
            TransferEncoding.BASE64 -> BASE64_ENCODER.encode(body.content)
            TransferEncoding.SEVEN_BIT, TransferEncoding.EIGHT_BIT -> body.content
        }

        return Entity(headerLines, encoded)
    }

    private fun renderMultipart(body: MultipartBody): Entity {
        val boundary = "=_fixture-$messageNumber-${++boundaryCounter}_"
        val delimiter = "--$boundary"

        val output = ByteArrayOutputStream()
        for (part in body.parts) {
            val entity = render(part)
            check(!String(entity.body, Charsets.ISO_8859_1).contains(delimiter)) {
                "Part content contains the MIME boundary $boundary"
            }

            output.writeAscii(delimiter + CRLF)
            entity.headerLines.forEach { output.writeAscii(it + CRLF) }
            output.writeAscii(CRLF)
            output.write(entity.body)
            output.writeAscii(CRLF)
        }
        output.writeAscii("$delimiter--")

        val contentType = contentType("multipart/${body.subtype}", listOf("boundary" to boundary))
        return Entity(listOf(HeaderFormat.field("Content-Type", contentType)), output.toByteArray())
    }

    private fun contentType(mimeType: String, parameters: List<Pair<String, String>>): String {
        return mimeType + parameters.joinToString("") { (name, value) -> HeaderFormat.parameter(name, value) }
    }

    private fun ByteArrayOutputStream.writeAscii(text: String) {
        write(text.toByteArray(Charsets.US_ASCII))
    }

    private class Entity(val headerLines: List<String>, val body: ByteArray)

    private companion object {
        const val CRLF = "\r\n"
        const val BASE64_LINE_LENGTH = 76
        val BASE64_ENCODER: Base64.Encoder = Base64.getMimeEncoder(BASE64_LINE_LENGTH, CRLF.toByteArray())
    }
}

/**
 * Quoted-printable encoding (RFC 2045). LF and CRLF in the input are hard line breaks written as CRLF; other bytes
 * are encoded so that encoded lines are at most 76 characters.
 */
internal object QuotedPrintable {
    private const val MAX_LINE_LENGTH = 76
    private const val PRINTABLE_MIN = 33
    private const val PRINTABLE_MAX = 126
    private const val SPACE = 0x20
    private const val TAB = 0x09
    private const val BYTE_MASK = 0xFF
    private val LF = '\n'.code.toByte()
    private val CR = '\r'.code.toByte()

    fun encode(content: ByteArray): ByteArray {
        val output = StringBuilder()
        splitLines(content).forEachIndexed { index, line ->
            if (index > 0) output.append("\r\n")
            encodeLine(line, output)
        }
        return output.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun splitLines(content: ByteArray): List<ByteArray> {
        val lines = mutableListOf<ByteArray>()
        var start = 0
        for (index in content.indices) {
            if (content[index] == LF) {
                val end = if (index > start && content[index - 1] == CR) index - 1 else index
                lines += content.copyOfRange(start, end)
                start = index + 1
            }
        }
        lines += content.copyOfRange(start, content.size)
        return lines
    }

    private fun encodeLine(line: ByteArray, output: StringBuilder) {
        var lineLength = 0
        line.forEachIndexed { index, byte ->
            val value = byte.toInt() and BYTE_MASK
            val isLast = index == line.lastIndex
            val token = if (isLiteral(value, isLast)) value.toChar().toString() else "=%02X".format(Locale.ROOT, value)

            // Leave room for the "=" of a soft line break.
            if (lineLength + token.length > MAX_LINE_LENGTH - 1) {
                output.append("=\r\n")
                lineLength = 0
            }
            output.append(token)
            lineLength += token.length
        }
    }

    private fun isLiteral(value: Int, isLastOnLine: Boolean): Boolean {
        val isPrintable = value in PRINTABLE_MIN..PRINTABLE_MAX && value != '='.code
        val isWhitespace = value == SPACE || value == TAB
        return isPrintable || (isWhitespace && !isLastOnLine)
    }
}
