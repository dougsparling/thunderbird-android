package net.thunderbird.mail.testserver.provision

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** A parsed IMAP data item. Numbers are [Atom]s; quoted strings and literals are both [Str]. */
internal sealed interface ImapToken {
    data class Atom(val value: String) : ImapToken

    class Str(val bytes: ByteArray) : ImapToken {
        val text: String get() = bytes.toString(Charsets.UTF_8)

        override fun toString(): String = "Str($text)"
    }

    data class ListNode(val items: List<ImapToken>) : ImapToken

    data object Nil : ImapToken
}

internal sealed interface ImapResponse {
    /** `+ text` */
    data class Continuation(val text: String) : ImapResponse

    /** `* OK|NO|BAD|BYE|PREAUTH [code] text` */
    data class UntaggedStatus(val status: String, val text: String) : ImapResponse

    /** Any other untagged response, e.g. `* CAPABILITY ...`, `* LIST ...`, `* 3 EXISTS`, `* 1 FETCH (...)`. */
    data class UntaggedData(val tokens: List<ImapToken>) : ImapResponse

    /** `tag OK|NO|BAD [code] text` */
    data class Tagged(val tag: String, val status: String, val text: String) : ImapResponse
}

/**
 * Reads IMAP server responses from a stream, including literals (`{n}` followed by `n` raw bytes).
 *
 * Status responses keep their text unparsed, since response text is free-form. Everything else is parsed into
 * [ImapToken]s up to the terminating CRLF.
 */
internal class ImapResponseReader(inputStream: InputStream) {
    private val input = PeekableInput(inputStream)

    fun readResponse(): ImapResponse {
        val first = input.peek()
        return when (first) {
            '+'.code -> {
                input.read()
                input.skipSpaces()
                ImapResponse.Continuation(readRestOfLine())
            }

            '*'.code -> {
                input.read()
                input.expect(' '.code)
                readUntagged()
            }

            else -> {
                val tag = readAtomString()
                input.expect(' '.code)
                val status = readAtomString().uppercase()
                input.skipSpaces()
                ImapResponse.Tagged(tag, status, readRestOfLine())
            }
        }
    }

    private fun readUntagged(): ImapResponse {
        val firstToken = readToken()
        val keyword = (firstToken as? ImapToken.Atom)?.value?.uppercase()
        if (keyword != null && keyword in STATUS_KEYWORDS) {
            input.skipSpaces()
            return ImapResponse.UntaggedStatus(keyword, readRestOfLine())
        }

        val tokens = mutableListOf(firstToken)
        readTokensUntil(tokens, endOfLine = true)
        return ImapResponse.UntaggedData(tokens)
    }

    private fun readTokensUntil(tokens: MutableList<ImapToken>, endOfLine: Boolean) {
        while (true) {
            input.skipSpaces()
            val next = input.peek()
            if (endOfLine && (next == '\r'.code || next == '\n'.code)) {
                input.readLineEnd()
                return
            } else if (!endOfLine && next == ')'.code) {
                input.read()
                return
            }
            tokens.add(readToken())
        }
    }

    private fun readToken(): ImapToken {
        return when (input.peek()) {
            '('.code -> {
                input.read()
                val items = mutableListOf<ImapToken>()
                readTokensUntil(items, endOfLine = false)
                ImapToken.ListNode(items)
            }

            '"'.code -> ImapToken.Str(readQuoted())

            '{'.code -> ImapToken.Str(readLiteral())

            '\r'.code, '\n'.code, ')'.code -> throw ImapProtocolException("Unexpected end of data in server response")

            else -> {
                val atom = readAtomString()
                if (atom.equals("NIL", ignoreCase = true)) ImapToken.Nil else ImapToken.Atom(atom)
            }
        }
    }

    /**
     * Reads an atom. Square brackets are kept together with their content, so `BODY[HEADER.FIELDS (SUBJECT)]` is one
     * atom.
     */
    private fun readAtomString(): String {
        val builder = StringBuilder()
        var bracketDepth = 0
        while (true) {
            val c = input.peek()
            val isDelimiter = c == ' '.code || c == '('.code || c == ')'.code || c == '"'.code
            val isLineEnd = c == '\r'.code || c == '\n'.code
            if (isLineEnd || (bracketDepth == 0 && isDelimiter)) break
            input.read()
            if (c == '['.code) bracketDepth++
            if (c == ']'.code && bracketDepth > 0) bracketDepth--
            builder.append(c.toChar())
        }
        if (builder.isEmpty()) throw ImapProtocolException("Expected atom in server response")
        return builder.toString()
    }

    private fun readQuoted(): ByteArray {
        input.expect('"'.code)
        val out = ByteArrayOutputStream()
        while (true) {
            var c = input.read()
            if (c == '"'.code) break
            if (c == '\r'.code || c == '\n'.code) throw ImapProtocolException("Unterminated quoted string")
            if (c == '\\'.code) c = input.read()
            out.write(c)
        }
        return out.toByteArray()
    }

    private fun readLiteral(): ByteArray {
        input.expect('{'.code)
        val digits = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == '}'.code) break
            if (c != '+'.code) digits.append(c.toChar())
        }
        val size = digits.toString().toIntOrNull()
            ?: throw ImapProtocolException("Invalid literal size '$digits' in server response")
        input.readLineEnd()
        return input.readBytes(size)
    }

    private fun readRestOfLine(): String {
        val out = ByteArrayOutputStream()
        while (input.peek() != '\r'.code && input.peek() != '\n'.code) {
            out.write(input.read())
        }
        input.readLineEnd()
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        val STATUS_KEYWORDS = setOf("OK", "NO", "BAD", "BYE", "PREAUTH")
    }
}

/** A byte stream with one byte of lookahead that fails with [EOFException] instead of returning -1. */
private class PeekableInput(private val input: InputStream) {
    private var peeked: Int = NONE

    fun peek(): Int {
        if (peeked == NONE) {
            peeked = input.read()
            if (peeked < 0) {
                peeked = NONE
                throw EOFException("Connection closed by IMAP server")
            }
        }
        return peeked
    }

    fun read(): Int {
        val c = peek()
        peeked = NONE
        return c
    }

    fun readBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        if (size > 0 && peeked != NONE) {
            bytes[offset++] = read().toByte()
        }
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            if (count < 0) throw EOFException("Connection closed while reading literal")
            offset += count
        }
        return bytes
    }

    fun expect(expected: Int) {
        val actual = read()
        if (actual != expected) {
            throw ImapProtocolException(
                "Expected '${expected.toChar()}' but got '${actual.toChar()}' in server response",
            )
        }
    }

    fun skipSpaces() {
        while (peek() == ' '.code) read()
    }

    fun readLineEnd() {
        if (peek() == '\r'.code) read()
        expect('\n'.code)
    }

    private companion object {
        const val NONE = -1
    }
}
