package net.thunderbird.mail.testserver.provision

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.matches
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import net.thunderbird.mail.testserver.fixture.FolderPath

class MappingAndHeadersTest {

    @OptIn(ExperimentalTime::class)
    @Test
    fun `internal date is formatted as RFC 3501 date-time in UTC with space-padded day`() {
        // Act
        val formatted = formatInternalDate(Instant.parse("2024-03-05T10:15:30Z"))

        // Assert
        assertThat(formatted).isEqualTo(" 5-Mar-2024 10:15:30 +0000")
    }

    @Test
    fun `folder paths map to server names and back`() {
        // Arrange
        val path = FolderPath.of("Archive", "2024")

        // Act & Assert
        assertThat(path.toServerName('.')).isEqualTo("Archive.2024")
        assertThat(path.toServerName('/')).isEqualTo("Archive/2024")
        assertThat(FolderPath.of("inbox").toServerName('/')).isEqualTo("INBOX")
        assertThat(folderPathFromServerName("Archive.2024", '.')).isEqualTo(path)
        assertThat(folderPathFromServerName("Inbox", '/')).isEqualTo(FolderPath.INBOX)
        assertThat(folderPathFromServerName("inbox.Sub", '.')).isEqualTo(FolderPath.of("INBOX", "Sub"))
    }

    @Test
    fun `nested paths are rejected for a flat server and segments must not contain the delimiter`() {
        assertFailure { FolderPath.of("a", "b").toServerName(null) }.isInstanceOf<IllegalArgumentException>()
        assertFailure { FolderPath.of("a.b").toServerName('.') }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `invalid keywords are rejected`() {
        assertThat(requireValidKeyword("\$Label1")).isEqualTo("\$Label1")
        assertFailure { requireValidKeyword("has space") }.isInstanceOf<IllegalArgumentException>()
        assertFailure { requireValidKeyword("\\Seen") }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `header values are unfolded and RFC 2047 decoded`() {
        // Arrange
        val header = "Message-ID: <abc@example.org>\r\nSubject: =?UTF-8?B?R3LDvMOfZQ==?=\r\n" +
            " =?ISO-8859-1?Q?_und_M=FCde?= plain\r\n\r\n"

        // Act
        val subject = MessageHeaders.value(header.toByteArray(), "subject")?.let(MessageHeaders::decodeEncodedWords)
        val messageId = MessageHeaders.value(header.toByteArray(), "Message-ID")

        // Assert
        assertThat(subject).isEqualTo("Grüße und Müde plain")
        assertThat(messageId).isEqualTo("<abc@example.org>")
        assertThat(MessageHeaders.value(header.toByteArray(), "From")).isNull()
    }

    @Test
    fun `undecodable encoded words are kept as sent`() {
        assertThat(MessageHeaders.decodeEncodedWords("=?x-unknown?B?AAAA?=")).isEqualTo("=?x-unknown?B?AAAA?=")
    }

    @Test
    fun `unique local parts are sanitised, lowercase and random`() {
        // Act
        val first = uniqueLocalPart("MySyncTest: handles Ümlauts & more!")
        val second = uniqueLocalPart("MySyncTest: handles Ümlauts & more!")

        // Assert
        assertThat(first).matches(Regex("mysynctest-handles-mlauts-more-[0-9a-f]{8}"))
        assertThat(first == second).isEqualTo(false)
        assertThat(uniqueLocalPart("!!!")).matches(Regex("user-[0-9a-f]{8}"))
    }

    @Test
    fun `json strings are escaped`() {
        assertThat(jsonString("a\"b\\c\n\u0001é")).isEqualTo("\"a\\\"b\\\\c\\u000a\\u0001é\"")
    }

    @Test
    fun `IMAP capabilities map to server capabilities`() {
        assertThat(mapImapCapabilities(setOf("IMAP4rev1", "create-special-use", "QRESYNC", "MOVE", "IDLE")))
            .isEqualTo(
                setOf(
                    ServerCapability.SPECIAL_USE_CREATE,
                    ServerCapability.CONDSTORE,
                    ServerCapability.MOVE,
                    ServerCapability.IDLE,
                ),
            )
    }
}
