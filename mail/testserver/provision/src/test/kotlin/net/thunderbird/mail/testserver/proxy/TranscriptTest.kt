package net.thunderbird.mail.testserver.proxy

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import kotlin.test.Test

class TranscriptTest {
    private var now = 0L
    private val clock = { now }

    @Test
    fun `formats entries with time, connection and marker`() {
        val testSubject = Transcript(nanoTime = clock)
        now = 12_000_000

        testSubject.record(1, "C:", "a1 NOOP")
        testSubject.record(null, "**", "rules applied: none")

        assertThat(testSubject.toString()).isEqualTo(
            "    12ms [c1] C: a1 NOOP\n" +
                "    12ms [proxy] ** rules applied: none\n",
        )
    }

    @Test
    fun `keeps the beginning and the end when over budget`() {
        val testSubject = Transcript(maxChars = 400, nanoTime = clock)

        repeat(100) { testSubject.record(1, "S:", "* $it EXISTS") }

        val text = testSubject.toString()
        assertThat(text).contains("S: * 0 EXISTS")
        assertThat(text).contains("S: * 99 EXISTS")
        assertThat(text).doesNotContain("S: * 50 EXISTS")
        assertThat(text).contains("[transcript truncated:")
        assertThat(text.length).isLessThan(500)
    }

    @Test
    fun `truncates very long entries`() {
        val testSubject = Transcript(maxEntryChars = 10, nanoTime = clock)

        testSubject.record(1, "S:", "x".repeat(50))

        assertThat(testSubject.toString()).contains("xxxxxxxxxx ... [entry truncated, 50 chars]")
    }

    @Test
    fun `escapeBytes shows control and non-ASCII bytes`() {
        val bytes =
            byteArrayOf('a'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(), 0, 0xFF.toByte(), '\\'.code.toByte())

        assertThat(escapeBytes(bytes)).isEqualTo("a\\r\\n\\x00\\xFF\\\\")
    }
}
