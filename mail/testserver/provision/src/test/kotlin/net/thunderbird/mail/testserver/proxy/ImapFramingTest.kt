package net.thunderbird.mail.testserver.proxy

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.random.Random
import kotlin.test.Test

class ImapFramingTest {

    @Test
    fun `splits lines and literals and marks the rest of a literal line as continuation`() {
        val testSubject = ImapFramer()
        val input = "* 1 FETCH (BODY[] {5}\r\nhello)\r\na1 OK done\r\n"

        val segments = testSubject.feed(input.toByteArray())

        assertThat(segments.map { it.describe() }).containsExactly(
            "line(* 1 FETCH (BODY[] {5}, literal=5)",
            "literal(hello, 0/5)",
            "line+())",
            "line(a1 OK done)",
        )
    }

    @Test
    fun `handles literals split across reads and non-synchronizing literals`() {
        val testSubject = ImapFramer()

        val first = testSubject.feed("a1 APPEND INBOX {6+}\r\nabc".toByteArray())
        val second = testSubject.feed("def\r\n".toByteArray())

        assertThat((first + second).map { it.describe() }).containsExactly(
            "line(a1 APPEND INBOX {6+}, literal=6)",
            "literal(abc, 0/6)",
            "literal(def, 3/6)",
            "line+()",
        )
    }

    @Test
    fun `does not treat lines inside a literal as lines`() {
        val testSubject = ImapFramer()
        val literal = "a2 UID STORE 1 +FLAGS (\\Seen)\r\n"

        val segments = testSubject.feed("a1 APPEND INBOX {${literal.length}}\r\n$literal\r\n".toByteArray())

        assertThat(segments.filterIsInstance<FrameSegment.Line>().map { it.text }).containsExactly(
            "a1 APPEND INBOX {${literal.length}}",
            "",
        )
    }

    @Test
    fun `flushes a long unterminated line and continues as continuation`() {
        val testSubject = ImapFramer(maxLineBytes = 5)

        val segments = testSubject.feed("abcdefg\r\n".toByteArray())

        assertThat(segments.map { it.describe() }).containsExactly("partial(abcde)", "line+(fg)")
    }

    @Test
    fun `flushPartialLine returns pending bytes once`() {
        val testSubject = ImapFramer()
        testSubject.feed("prompt> ".toByteArray())

        val flushed = testSubject.flushPartialLine()

        assertThat(flushed?.text).isEqualTo("prompt> ")
        assertThat(testSubject.hasPartialLine).isFalse()
        assertThat(testSubject.flushPartialLine()).isNull()
    }

    @Test
    fun `concatenated segments equal the input for random binary data`() {
        val testSubject = ImapFramer(maxLineBytes = 100)
        val random = Random(42)
        val input = random.nextBytes(50_000) + "x {300}\r\n".toByteArray() + random.nextBytes(1_000)

        var position = 0
        val output = mutableListOf<Byte>()
        while (position < input.size) {
            val count = minOf(random.nextInt(1, 700), input.size - position)
            testSubject.feed(input, position, count).forEach { output += it.bytes.toList() }
            position += count
        }
        testSubject.flushPartialLine()?.let { output += it.bytes.toList() }

        assertThat(output.toByteArray().contentEquals(input)).isTrue()
    }

    @Test
    fun `line terminators are detected`() {
        val testSubject = ImapFramer()

        val (crlf, lf) = testSubject.feed("a\r\nb\n".toByteArray()).map { it as FrameSegment.Line }

        assertThat(crlf.hasCrLf).isTrue()
        assertThat(lf.hasCrLf).isFalse()
        assertThat(lf.text).isEqualTo("b")
    }

    @Test
    fun `parseCommand extracts tag, command name and arguments`() {
        assertThat(ImapSyntax.parseCommand("a1 login user pass"))
            .isEqualTo(ImapSyntax.Command("a1", "LOGIN", "user pass"))
        assertThat(ImapSyntax.parseCommand("7 uid fetch 1:* (FLAGS)"))
            .isEqualTo(ImapSyntax.Command("7", "UID FETCH", "1:* (FLAGS)"))
        assertThat(ImapSyntax.parseCommand("a2 NOOP")).isEqualTo(ImapSyntax.Command("a2", "NOOP", ""))
    }

    @Test
    fun `parseCommand rejects lines that are not commands`() {
        assertThat(ImapSyntax.parseCommand("DONE")).isNull()
        assertThat(ImapSyntax.parseCommand("dXNlcgB1c2VyAHBhc3M=")).isNull()
        assertThat(ImapSyntax.parseCommand("* OK hello")).isNull()
        assertThat(ImapSyntax.parseCommand("+ go ahead")).isNull()
        assertThat(ImapSyntax.parseCommand("")).isNull()
    }

    @Test
    fun `responseTag returns only tags of tagged responses`() {
        assertThat(ImapSyntax.responseTag("a1 OK done")).isEqualTo("a1")
        assertThat(ImapSyntax.responseTag("* 3 EXISTS")).isNull()
        assertThat(ImapSyntax.responseTag("+ ready")).isNull()
        assertThat(ImapSyntax.isOk("a1 ok done")).isTrue()
        assertThat(ImapSyntax.isOk("a1 NO failed")).isFalse()
    }

    @Test
    fun `normalizeCommandName upper-cases and collapses whitespace`() {
        assertThat(ImapSyntax.normalizeCommandName("  uid   fetch ")).isEqualTo("UID FETCH")
    }

    private fun FrameSegment.describe(): String = when (this) {
        is FrameSegment.Line -> {
            val kind = when {
                !complete -> "partial"
                continuation -> "line+"
                else -> "line"
            }
            "$kind($text" + (literalFollows?.let { ", literal=$it" } ?: "") + ")"
        }

        is FrameSegment.LiteralData -> "literal(${String(bytes)}, $offset/$literalSize)"
    }
}
