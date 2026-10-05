package net.thunderbird.mail.testserver.provision

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.single
import kotlin.test.Test

class ImapResponseParsingTest {

    private fun readAll(text: String): List<ImapResponse> {
        val testSubject = ImapResponseReader(text.byteInputStream(Charsets.UTF_8))
        val responses = mutableListOf<ImapResponse>()
        while (true) {
            val response = testSubject.readResponse()
            responses.add(response)
            if (response is ImapResponse.Tagged) return responses
        }
    }

    @Test
    fun `reads status, continuation and tagged responses with their text unparsed`() {
        // Arrange
        val text = "* OK [CAPABILITY IMAP4rev1 LITERAL+] Ready (really\r\n" +
            "+ go ahead\r\n" +
            "A1 NO [CANNOT] Can't \"do\" it\r\n"

        // Act
        val responses = readAll(text)

        // Assert
        assertThat(responses).containsExactly(
            ImapResponse.UntaggedStatus("OK", "[CAPABILITY IMAP4rev1 LITERAL+] Ready (really"),
            ImapResponse.Continuation("go ahead"),
            ImapResponse.Tagged("A1", "NO", "[CANNOT] Can't \"do\" it"),
        )
    }

    @Test
    fun `parses LIST responses with special-use flags, quoted, literal and NIL delimiter entries`() {
        // Arrange
        val text = "* LIST (\\HasNoChildren \\Archive) \"/\" \"Archive/2024\"\r\n" +
            "* LIST (\\Noselect) \".\" {14}\r\nEntw&APw-rfe.x\r\n" +
            "* LIST () NIL INBOX\r\n" +
            "* LIST (\\Sent) \"\\\\\" \"Sent \\\"Items\\\"\"\r\n" +
            "A2 OK LIST done\r\n"

        // Act
        val entries = parseListEntries(readAll(text))

        // Assert
        assertThat(entries).containsExactly(
            ListEntry("Archive/2024", '/', setOf("\\HASNOCHILDREN", "\\ARCHIVE")),
            ListEntry("Entwürfe.x", '.', setOf("\\NOSELECT")),
            ListEntry("INBOX", null, emptySet()),
            ListEntry("Sent \"Items\"", '\\', setOf("\\SENT")),
        )
    }

    @Test
    fun `parses FETCH responses with literals containing parentheses and CRLF, merging split responses`() {
        // Arrange
        val header = "Subject: (odd) )\r\nMessage-ID: <1@x>\r\n\r\n"
        val text = "* 1 FETCH (UID 7 FLAGS (\\Seen \$label1) BODY[HEADER.FIELDS (SUBJECT MESSAGE-ID)] " +
            "{${header.length}}\r\n$header)\r\n" +
            "* 2 FETCH (FLAGS ())\r\n" +
            "* 2 FETCH (UID 9 BODY[HEADER.FIELDS (\"SUBJECT\" \"MESSAGE-ID\")] NIL)\r\n" +
            "* 3 EXISTS\r\n" +
            "A3 OK done\r\n"

        // Act
        val messages = parseFetchResponses(readAll(text))

        // Assert
        assertThat(messages.map { it.uid }).containsExactly(7L, 9L)
        assertThat(messages[0].flags).containsExactly("\\Seen", "\$label1")
        assertThat(messages[0].header!!.toString(Charsets.UTF_8)).isEqualTo(header)
        assertThat(messages[1].flags).isEqualTo(emptyList())
        assertThat(messages[1].header).isNull()
    }

    @Test
    fun `untagged data keeps bracketed atoms together`() {
        // Arrange
        val text = "* OK [PERMANENTFLAGS (\\Seen \\*)] Limited\r\n* CAPABILITY IMAP4rev1 AUTH=PLAIN\r\nA1 OK\r\n"

        // Act
        val responses = readAll(text)

        // Assert
        assertThat(responses[1]).isInstanceOf<ImapResponse.UntaggedData>().transform { data ->
            data.tokens.map { (it as ImapToken.Atom).value }
        }.containsExactly("CAPABILITY", "IMAP4rev1", "AUTH=PLAIN")
        assertThat(responses.filterIsInstance<ImapResponse.Tagged>()).single()
            .isEqualTo(ImapResponse.Tagged("A1", "OK", ""))
    }

    @Test
    fun `capability response code is parsed from status text`() {
        // Act
        val capabilities = parseCapabilityCode("[CAPABILITY IMAP4rev1 literal+ IDLE] Logged in")

        // Assert
        assertThat(capabilities).isEqualTo(setOf("IMAP4REV1", "LITERAL+", "IDLE"))
    }
}
