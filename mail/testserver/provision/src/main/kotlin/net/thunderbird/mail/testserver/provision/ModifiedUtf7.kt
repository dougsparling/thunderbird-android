package net.thunderbird.mail.testserver.provision

import java.util.Base64

/** Mailbox name encoding from RFC 3501 section 5.1.3 ("modified UTF-7"). */
internal object ModifiedUtf7 {
    private val encoder = Base64.getEncoder().withoutPadding()
    private val decoder = Base64.getDecoder()

    fun encode(name: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < name.length) {
            val c = name[index]
            when {
                c == '&' -> {
                    out.append("&-")
                    index++
                }

                c.isDirectlyEncodable() -> {
                    out.append(c)
                    index++
                }

                else -> {
                    val start = index
                    while (index < name.length && !name[index].isDirectlyEncodable()) index++
                    val utf16 = name.substring(start, index).toByteArray(Charsets.UTF_16BE)
                    out.append('&').append(encoder.encodeToString(utf16).replace('/', ',')).append('-')
                }
            }
        }
        return out.toString()
    }

    fun decode(name: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < name.length) {
            val c = name[index]
            if (c != '&') {
                out.append(c)
                index++
                continue
            }
            val end = name.indexOf('-', startIndex = index + 1)
            require(end >= 0) { "Unterminated modified UTF-7 sequence in mailbox name" }
            if (end == index + 1) {
                out.append('&')
            } else {
                val base64 = name.substring(index + 1, end).replace(',', '/')
                out.append(decoder.decode(base64).toString(Charsets.UTF_16BE))
            }
            index = end + 1
        }
        return out.toString()
    }

    private fun Char.isDirectlyEncodable(): Boolean = this in ' '..'~'
}
