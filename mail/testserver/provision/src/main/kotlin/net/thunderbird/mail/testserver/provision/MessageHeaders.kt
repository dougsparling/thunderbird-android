package net.thunderbird.mail.testserver.provision

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64

/** Just enough header parsing for [ServerMessageState]: unfolding, lookup by name and RFC 2047 decoding. */
internal object MessageHeaders {
    private val ENCODED_WORD = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=")
    private val WHITESPACE_BETWEEN_ENCODED_WORDS = Regex("(\\?=)\\s+(=\\?)")
    private val FOLDED_LINE_BREAK = Regex("\r?\n[ \t]")
    private const val HEX_RADIX = 16

    /** Returns the unfolded value of the first header called [name], or null. Unencoded 8-bit is read as UTF-8. */
    fun value(header: ByteArray, name: String): String? {
        val unfolded = header.toString(Charsets.UTF_8).replace(FOLDED_LINE_BREAK, " ")
        return unfolded.lineSequence()
            .map { it.trimEnd('\r') }
            .firstOrNull { line ->
                line.length > name.length && line[name.length] == ':' && line.startsWith(name, true)
            }
            ?.substring(name.length + 1)
            ?.trim()
    }

    /** Decodes RFC 2047 encoded words. Words with an unknown charset or bad encoding are left as they are. */
    fun decodeEncodedWords(value: String): String =
        ENCODED_WORD.replace(value.replace(WHITESPACE_BETWEEN_ENCODED_WORDS, "$1$2")) { match ->
            val (charsetName, encoding, text) = match.destructured
            runCatching {
                val charset = Charset.forName(charsetName.substringBefore('*'))
                val bytes = if (encoding.equals("B", ignoreCase = true)) {
                    Base64.getDecoder().decode(text)
                } else {
                    decodeQ(text)
                }
                bytes.toString(charset)
            }.getOrDefault(match.value)
        }

    private fun decodeQ(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < text.length) {
            val c = text[index]
            when {
                c == '_' -> out.write(' '.code)

                c == '=' && index + 2 < text.length -> {
                    out.write(text.substring(index + 1).take(2).toInt(HEX_RADIX))
                    index += 2
                }

                else -> out.write(c.code)
            }
            index++
        }
        return out.toByteArray()
    }
}
