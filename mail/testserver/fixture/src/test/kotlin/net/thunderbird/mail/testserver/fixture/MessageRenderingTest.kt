package net.thunderbird.mail.testserver.fixture

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.each
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import assertk.assertions.prop
import assertk.assertions.startsWith
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.apache.james.mime4j.codec.DecodeMonitor
import org.apache.james.mime4j.dom.BinaryBody
import org.apache.james.mime4j.dom.Entity
import org.apache.james.mime4j.dom.Message
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.stream.MimeConfig

@OptIn(ExperimentalTime::class)
class MessageRenderingTest {

    @Test
    fun `should render simple text message with all headers and CRLF line endings`() {
        val testSubject = buildMessage {
            from("Bob <bob@example.org>")
            to("alice@example.com", "Carol <carol@example.com>")
            cc("dave@example.com")
            bcc("eve@example.com")
            replyTo("replies@example.org")
            subject("Hello")
            date(Instant.parse("2024-03-01T10:00:00Z"))
            header("X-Custom", "value")
            text("Plain body\nsecond line")
        }

        assertThat(testSubject.rfc822.decodeToString()).isEqualTo(
            "Date: Fri, 1 Mar 2024 10:00:00 +0000\r\n" +
                "From: Bob <bob@example.org>\r\n" +
                "To: alice@example.com, Carol <carol@example.com>\r\n" +
                "Cc: dave@example.com\r\n" +
                "Bcc: eve@example.com\r\n" +
                "Reply-To: replies@example.org\r\n" +
                "Subject: Hello\r\n" +
                "Message-ID: <fixture-1@testserver.invalid>\r\n" +
                "X-Custom: value\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n" +
                "Content-Transfer-Encoding: quoted-printable\r\n" +
                "\r\n" +
                "Plain body\r\n" +
                "second line\r\n",
        )
        assertThat(testSubject.messageId).isEqualTo("<fixture-1@testserver.invalid>")
    }

    @Test
    fun `should render identical bytes for identical input`() {
        val block: FolderBuilder.() -> Unit = {
            message {
                subject("Grüße")
                mixed {
                    alternative {
                        text("plain")
                        html("<p>html</p>")
                    }
                    attachment(name = "a.bin", content = ByteArray(300) { it.toByte() })
                }
            }
        }

        val first = userFixture { inbox(block) }.folders.single().messages.single()
        val second = userFixture { inbox(block) }.folders.single().messages.single()

        assertThat(first.rfc822.contentEquals(second.rfc822)).isTrue()
    }

    @Test
    fun `should encode non-ASCII headers as RFC 2047 encoded words`() {
        val subject = "Grüße aus Köln – ☃ and 😀"

        val testSubject = buildMessage {
            from("Jörg Müller <joerg@example.org>")
            to("\"Doe, John\" <john@example.org>")
            subject(subject)
            header("X-Note", "naïve")
        }

        val rendered = testSubject.rfc822.decodeToString()
        assertThat(rendered).all {
            contains("Subject: =?UTF-8?B?")
            contains("From: =?UTF-8?B?")
            contains("<joerg@example.org>")
            contains("To: \"Doe, John\" <john@example.org>")
            contains("X-Note: =?UTF-8?B?")
        }
        assertThat(testSubject.rfc822.all { it >= 0 }).isTrue()

        val parsed = parse(testSubject.rfc822)
        assertThat(parsed.subject).isEqualTo(subject)
        assertThat(parsed.from.single().name).isEqualTo("Jörg Müller")
        assertThat(parsed.from.single().address).isEqualTo("joerg@example.org")
        assertThat(parsed.to.flatten().single().name).isEqualTo("Doe, John")
        assertThat(testSubject.subject).isEqualTo(subject)
    }

    @Test
    fun `should fold long headers`() {
        val asciiSubject = (1..40).joinToString(" ") { "word$it" }
        val unicodeSubject = "ü".repeat(100)
        val recipients = (1..20).map { "Recipient $it <recipient$it@example.org>" }

        val ascii = buildMessage {
            subject(asciiSubject)
            to(*recipients.toTypedArray())
        }
        val unicode = buildMessage { subject(unicodeSubject) }

        for (message in listOf(ascii, unicode)) {
            val headerLines = headerSection(message).split("\r\n")
            assertThat(headerLines).each { it.prop(String::length).isLessThanOrEqualTo(78) }
        }
        assertThat(headerSection(ascii)).contains("\r\n word")
        assertThat(parse(ascii.rfc822).subject).isEqualTo(asciiSubject)
        assertThat(parse(ascii.rfc822).to.flatten().map { it.address }).isEqualTo(
            (1..20).map { "recipient$it@example.org" },
        )
        assertThat(parse(unicode.rfc822).subject).isEqualTo(unicodeSubject)
    }

    @Test
    fun `should reject header injection`() {
        assertFailure {
            buildMessage { subject("Hello\r\nBcc: evil@example.org") }
        }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `should reject headers generated by the DSL`() {
        assertFailure {
            buildMessage { header("content-type", "text/html") }
        }.isInstanceOf<IllegalArgumentException>().messageContains("generated by the DSL")
    }

    @Test
    fun `should reject a second body`() {
        assertFailure {
            buildMessage {
                text("one")
                text("two")
            }
        }.isInstanceOf<IllegalStateException>()
    }

    @Test
    fun `should encode text as quoted-printable with lines of at most 76 characters`() {
        val body = "Grüße = ${"x".repeat(200)}\nline with trailing space \nend"

        val testSubject = buildMessage { text(body) }

        val bodySection = bodySection(testSubject)
        assertThat(bodySection.split("\r\n")).each { it.prop(String::length).isLessThanOrEqualTo(76) }
        assertThat(bodySection).all {
            contains("Gr=C3=BC=C3=9Fe =3D ")
            contains("space=20\r\n")
        }
        // In a single-part message the final CRLF belongs to the body.
        assertThat(textOf(parse(testSubject.rfc822))).isEqualTo(body.replace("\n", "\r\n") + "\r\n")
    }

    @Test
    fun `should render multipart alternative`() {
        val testSubject = buildMessage {
            subject("Nested")
            alternative {
                text("plain")
                html("<p>html</p>")
            }
        }

        val parsed = parse(testSubject.rfc822)
        assertThat(parsed.mimeType).isEqualTo("multipart/alternative")
        val parts = (parsed.body as Multipart).bodyParts
        assertThat(parts.map { it.mimeType }).containsExactly("text/plain", "text/html")
        assertThat(parts.map { textOf(it) }).containsExactly("plain", "<p>html</p>")
        assertThat(testSubject.rfc822.decodeToString()).all {
            contains("Content-Type: multipart/alternative; boundary=\"=_fixture-1-1_\"\r\n")
            contains("\r\n--=_fixture-1-1_--\r\n")
        }
    }

    @Test
    fun `should render mixed with nested alternative and attachment`() {
        val attachmentContent = ByteArray(1000) { (it * 7).toByte() }

        val testSubject = buildMessage {
            mixed {
                alternative {
                    text("plain")
                    html("<p>html</p>")
                }
                attachment(name = "a.pdf", mimeType = "application/pdf", content = attachmentContent)
            }
        }

        val parsed = parse(testSubject.rfc822)
        assertThat(parsed.mimeType).isEqualTo("multipart/mixed")
        val (alternative, attachment) = (parsed.body as Multipart).bodyParts
        assertThat(alternative.mimeType).isEqualTo("multipart/alternative")
        assertThat((alternative.body as Multipart).bodyParts.map { it.mimeType })
            .containsExactly("text/plain", "text/html")
        assertThat(attachment.mimeType).isEqualTo("application/pdf")
        assertThat(attachment.dispositionType).isEqualTo("attachment")
        assertThat(attachment.filename).isEqualTo("a.pdf")
        assertThat(attachment.contentTransferEncoding).isEqualTo("base64")
        assertThat((attachment.body as BinaryBody).inputStream.readBytes().contentEquals(attachmentContent)).isTrue()

        val base64Lines = bodySection(testSubject).split("\r\n").filter { it.matches(Regex("[A-Za-z0-9+/=]{20,}")) }
        assertThat(base64Lines.dropLast(1)).each { it.prop(String::length).isEqualTo(76) }
        assertThat(testSubject.rfc822.decodeToString()).all {
            contains("boundary=\"=_fixture-1-1_\"")
            contains("boundary=\"=_fixture-1-2_\"")
        }
    }

    @Test
    fun `should render related with inline content id`() {
        val testSubject = buildMessage {
            related {
                html("<img src=\"cid:logo@example\">")
                attachment(
                    name = "logo.png",
                    content = byteArrayOf(1, 2, 3),
                    mimeType = "image/png",
                    inline = true,
                    contentId = "logo@example",
                )
            }
        }

        val parsed = parse(testSubject.rfc822)
        assertThat(parsed.mimeType).isEqualTo("multipart/related")
        val image = (parsed.body as Multipart).bodyParts[1]
        assertThat(image.dispositionType).isEqualTo("inline")
        assertThat(image.header.getField("Content-ID").body).isEqualTo("<logo@example>")
    }

    @Test
    fun `should encode non-ASCII attachment names with RFC 2231`() {
        val testSubject = buildMessage {
            attachment(name = "résumé.txt", content = byteArrayOf(65))
        }

        assertThat(testSubject.rfc822.decodeToString()).all {
            contains("Content-Type: application/octet-stream; name*=UTF-8''r%C3%A9sum%C3%A9.txt\r\n")
            contains("Content-Disposition: attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.txt\r\n")
        }
    }

    @Test
    fun `should render generic part`() {
        val embedded = "Subject: inner\r\n\r\ninner body\r\n"

        val testSubject = buildMessage {
            mixed {
                text("see attached")
                part("message/rfc822") {
                    content(embedded)
                    disposition("attachment", fileName = "inner.eml")
                    header("Content-Description", "Forwarded")
                }
                part("text/calendar") {
                    content("BEGIN:VCALENDAR")
                    parameter("method", "REQUEST")
                }
            }
        }

        val rendered = testSubject.rfc822.decodeToString()
        assertThat(rendered).all {
            contains("Content-Type: message/rfc822\r\nContent-Transfer-Encoding: 7bit\r\n")
            contains("Content-Disposition: attachment; filename=inner.eml\r\n")
            contains("Content-Description: Forwarded\r\n\r\n$embedded")
            contains("Content-Type: text/calendar; charset=UTF-8; method=REQUEST\r\n")
        }
        val parts = (parse(testSubject.rfc822).body as Multipart).bodyParts
        assertThat(parts.map { it.mimeType }).containsExactly("text/plain", "message/rfc822", "text/calendar")
        assertThat((parts[1].body as Message).subject).isEqualTo("inner")
    }

    @Test
    fun `should fail when unencoded content contains the boundary`() {
        assertFailure {
            buildMessage {
                mixed {
                    part("text/plain") {
                        content("--=_fixture-1-1_\r\n")
                        encoding(TransferEncoding.SEVEN_BIT)
                    }
                }
            }
        }.isInstanceOf<IllegalStateException>().messageContains("boundary")
    }

    @Test
    fun `should render empty text body when none is declared`() {
        val testSubject = buildMessage { subject("No body") }

        assertThat(testSubject.rfc822.decodeToString()).startsWith("Date: ")
        assertThat(bodySection(testSubject)).isEqualTo("\r\n")
        assertThat(parse(testSubject.rfc822).mimeType).isEqualTo("text/plain")
    }

    @Test
    fun `should use different boundaries for different messages`() {
        val testSubject = userFixture {
            inbox {
                repeat(2) {
                    message { mixed { text("x") } }
                }
            }
        }

        val (first, second) = testSubject.folders.single().messages.map { it.rfc822.decodeToString() }
        assertThat(first).contains("=_fixture-1-1_")
        assertThat(second).all {
            contains("=_fixture-2-1_")
            doesNotContain("=_fixture-1-1_")
        }
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `should only use CRLF line endings`() {
        val testSubject = buildMessage {
            subject("x".repeat(200).chunked(10).joinToString(" "))
            mixed {
                text("a\nb\r\nc\rd")
                attachment(name = "a", content = ByteArray(500) { it.toByte() })
            }
        }

        val bytes = testSubject.rfc822
        val badLineEnding = bytes.indices.any { index ->
            val isBareLf = bytes[index] == LF && (index == 0 || bytes[index - 1] != CR)
            val isBareCr = bytes[index] == CR && (index == bytes.lastIndex || bytes[index + 1] != LF)
            isBareLf || isBareCr
        }
        assertThat(badLineEnding).isEqualTo(false)
    }

    private fun buildMessage(block: MessageBuilder.() -> Unit): MessageFixture {
        return userFixture { inbox { message(block) } }.folders.single().messages.single()
    }

    private fun headerSection(message: MessageFixture): String {
        return message.rfc822.decodeToString().substringBefore("\r\n\r\n")
    }

    private fun bodySection(message: MessageFixture): String {
        return message.rfc822.decodeToString().substringAfter("\r\n\r\n")
    }

    private fun parse(bytes: ByteArray): Message {
        val builder = DefaultMessageBuilder().apply {
            setMimeEntityConfig(MimeConfig.STRICT)
            setDecodeMonitor(DecodeMonitor.STRICT)
        }
        return builder.parseMessage(ByteArrayInputStream(bytes))
    }

    private fun textOf(entity: Entity): String {
        return (entity.body as TextBody).reader.use { it.readText() }
    }

    private companion object {
        val LF = '\n'.code.toByte()
        val CR = '\r'.code.toByte()
    }
}
