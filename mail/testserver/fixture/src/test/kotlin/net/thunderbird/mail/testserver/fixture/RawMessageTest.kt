package net.thunderbird.mail.testserver.fixture

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class RawMessageTest {

    @Test
    fun `should pass raw bytes through unchanged`() {
        val bytes = "Subject: actual\nno CRLF here \u0000\n".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(-1, -2)
        val internalDate = Instant.parse("2023-06-01T12:00:00Z")

        val testSubject = userFixture {
            folder("Weird") {
                raw(
                    bytes,
                    subject = "declared subject",
                    messageId = "<declared@example.org>",
                    flags = setOf(SystemFlag.SEEN),
                    keywords = setOf("\$Junk"),
                    internalDate = internalDate,
                )
            }
        }

        val message = testSubject.folders.single().messages.single()
        assertThat(message.rfc822.contentEquals(bytes)).isTrue()
        assertThat(message.subject).isEqualTo("declared subject")
        assertThat(message.messageId).isEqualTo("<declared@example.org>")
        assertThat(message.flags).isEqualTo(setOf(SystemFlag.SEEN))
        assertThat(message.keywords).isEqualTo(setOf("\$Junk"))
        assertThat(message.internalDate).isEqualTo(internalDate)
    }

    @Test
    fun `should copy raw bytes`() {
        val bytes = byteArrayOf(1, 2, 3)

        val testSubject = userFixture { inbox { raw(bytes) } }
        bytes[0] = 9

        val message = testSubject.folders.single().messages.single()
        assertThat(message.rfc822.toList()).isEqualTo(listOf<Byte>(1, 2, 3))
        assertThat(message.subject).isNull()
        assertThat(message.messageId).isNull()
        assertThat(message.internalDate).isEqualTo(FixtureDefaults.BASE_DATE)
    }

    @Test
    fun `should load eml from classpath resource`() {
        val expected = javaClass.classLoader.getResource("fixtures/broken-charset.eml")!!.readBytes()

        val testSubject = userFixture {
            folder("Weird") {
                eml("fixtures/broken-charset.eml", subject = "declared subject")
                eml("/fixtures/broken-charset.eml")
            }
        }

        val messages = testSubject.folders.single().messages
        assertThat(messages.all { it.rfc822.contentEquals(expected) }).isTrue()
        assertThat(messages.map { it.subject }).isEqualTo(listOf("declared subject", null))
    }

    @Test
    fun `should fail for missing resource`() {
        assertFailure {
            userFixture { inbox { eml("fixtures/missing.eml") } }
        }.isInstanceOf<IllegalArgumentException>()
            .hasMessage("Classpath resource not found: fixtures/missing.eml")
    }
}
