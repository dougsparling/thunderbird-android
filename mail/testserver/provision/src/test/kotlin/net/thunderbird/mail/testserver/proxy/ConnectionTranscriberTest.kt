package net.thunderbird.mail.testserver.proxy

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import kotlin.test.Test

class ConnectionTranscriberTest {
    private val transcript = Transcript(nanoTime = { 0L })
    private val testSubject = ConnectionTranscriber(connectionId = 1, transcript = transcript, literalPreviewBytes = 8)
    private val clientFramer = ImapFramer()
    private val serverFramer = ImapFramer()

    @Test
    fun `redacts LOGIN arguments`() {
        client("a1 LOGIN alice s3cret\r\n")
        server("a1 OK LOGIN completed\r\n")
        client("a2 NOOP\r\n")

        assertThat(lines()).containsExactly(
            "C: a1 LOGIN [redacted]",
            "S: a1 OK LOGIN completed",
            "C: a2 NOOP",
        )
    }

    @Test
    fun `redacts LOGIN arguments sent as literals`() {
        client("a1 LOGIN {5}\r\n")
        server("+ go ahead\r\n")
        client("alice {6}\r\n")
        server("+ go ahead\r\n")
        client("s3cret\r\n")
        client("a2 NOOP\r\n")

        assertThat(lines()).containsExactly(
            "C: a1 LOGIN [redacted]",
            "S: + go ahead",
            "C: [literal 5 bytes] [redacted]",
            "C: [redacted]",
            "S: + go ahead",
            "C: [literal 6 bytes] [redacted]",
            "C: [redacted]",
            "C: a2 NOOP",
        )
        assertThat(transcript.toString()).doesNotContain("alice")
    }

    @Test
    fun `redacts AUTHENTICATE initial response and continuation lines until the tagged response`() {
        client("a1 AUTHENTICATE PLAIN\r\n")
        server("+ \r\n")
        client("AGFsaWNlAHMzY3JldA==\r\n")
        server("a1 OK AUTHENTICATE completed\r\n")
        client("a2 AUTHENTICATE PLAIN AGFsaWNlAHMzY3JldA==\r\n")
        server("a2 OK done\r\n")
        client("a3 NOOP\r\n")

        assertThat(lines()).containsExactly(
            "C: a1 AUTHENTICATE PLAIN",
            "S: + ",
            "C: [redacted] (authentication exchange)",
            "S: a1 OK AUTHENTICATE completed",
            "C: a2 AUTHENTICATE PLAIN [redacted]",
            "S: a2 OK done",
            "C: a3 NOOP",
        )
    }

    @Test
    fun `abbreviates literals and escapes binary data`() {
        server("* 1 FETCH (BODY[] {20}\r\n")
        server("\u0000\u0001abc\r\ndefghijklmnop")
        server(")\r\n")

        assertThat(lines()).containsExactly(
            "S: * 1 FETCH (BODY[] {20}",
            "S: [literal 20 bytes] \\x00\\x01abc\\r\\nd ... [+12 more bytes]",
            "S: )",
        )
    }

    @Test
    fun `reports literals cut off by the connection closing`() {
        server("* 1 FETCH (BODY[] {100}\r\n")
        server("abc")

        testSubject.finish(Direction.DOWNSTREAM)

        assertThat(lines()).containsExactly(
            "S: * 1 FETCH (BODY[] {100}",
            "S: [literal 100 bytes, connection closed after 3 bytes] abc",
        )
    }

    @Test
    fun `marks bare LF and unterminated lines`() {
        server("* OK hi\n")
        testSubject.line(Direction.DOWNSTREAM, FrameSegment.Line("220 ".toByteArray(), false, false, null))

        assertThat(lines()).containsExactly("S: * OK hi [bare LF]", "S: 220  [no line terminator yet]")
    }

    private fun client(text: String) = feed(Direction.UPSTREAM, clientFramer, text)

    private fun server(text: String) = feed(Direction.DOWNSTREAM, serverFramer, text)

    private fun feed(direction: Direction, framer: ImapFramer, text: String) {
        framer.feed(text.toByteArray(Charsets.ISO_8859_1)).forEach { segment ->
            when (segment) {
                is FrameSegment.Line -> testSubject.line(direction, segment)
                is FrameSegment.LiteralData -> testSubject.literal(direction, segment)
            }
        }
    }

    private fun lines(): List<String> = transcript.toString().lines().filter { it.isNotEmpty() }
        .map { it.substringAfter("[c1] ") }
}
