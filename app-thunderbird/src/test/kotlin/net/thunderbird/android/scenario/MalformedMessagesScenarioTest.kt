package net.thunderbird.android.scenario

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isNotNull
import assertk.assertions.matches
import assertk.assertions.single
import kotlin.test.Test
import net.thunderbird.android.scenario.harness.ClientMessage
import net.thunderbird.android.scenario.harness.ScenarioTest
import net.thunderbird.mail.testserver.fixture.FolderPath

/**
 * The inbox holds a batch of messages that break MIME and RFC 5322, served from the fixture's `raw()` bytes: an
 * unmatched multipart boundary, an unknown charset, NUL bytes in the body, raw 8-bit characters in the headers, a
 * message with neither Date nor Message-ID, and a header line far longer than the standard allows, next to two
 * well-formed messages. Pulling to refresh must list every message with its subject, so one malformed message never
 * hides the rest of the mailbox.
 *
 * Undeclared 8-bit header bytes have no defined charset. Today the app shows the Latin-1 subject's accented letters as
 * replacement characters; the test only requires that the ASCII letters around them survive, so a better guess also
 * passes. The other subjects are compared exactly.
 */
class MalformedMessagesScenarioTest : ScenarioTest() {

    @Test
    fun `pull to refresh lists malformed and well-formed messages`() = scenario {
        val user = server.user {
            inbox {
                raw(brokenMultipartBoundary(), subject = BROKEN_BOUNDARY_SUBJECT)
                raw(unknownCharset(), subject = UNKNOWN_CHARSET_SUBJECT)
                raw(nulBytesInBody(), subject = NUL_BYTES_SUBJECT)
                raw(rawEightBitHeaders(), subject = EIGHT_BIT_METADATA_SUBJECT)
                raw(noDateOrMessageId(), subject = NO_DATE_SUBJECT)
                raw(veryLongHeaderLine(), subject = VERY_LONG_HEADER_SUBJECT)
                message {
                    subject(WELL_FORMED_SUBJECT_1)
                    from("alice@example.org")
                    text("A normal message.")
                }
                message {
                    subject(WELL_FORMED_SUBJECT_2)
                    from("bob@example.org")
                    text("Another normal message.")
                }
            }
        }
        val account = client.account(user)

        driver.pullToRefresh(account, FolderPath.INBOX)

        val subjects = driver.messageList(account, FolderPath.INBOX).map(ClientMessage::subject)

        // All eight messages are listed, each with a subject.
        assertThat(subjects).hasSize(8)
        KNOWN_SUBJECTS.forEach { subject -> assertThat(subjects).contains(subject) }
        // The one other listed message is the 8-bit one, with its ASCII letters intact.
        assertThat(subjects.filterNot { it in KNOWN_SUBJECTS }).single().isNotNull().matches(EIGHT_BIT_SUBJECT_SHOWN)
    }

    /** A multipart header whose declared boundary doesn't match the one used in the body. */
    private fun brokenMultipartBoundary(): ByteArray = rawMessage(
        "From: Alice <alice@example.org>",
        "To: Scenario User <user@example.org>",
        "Subject: $BROKEN_BOUNDARY_SUBJECT",
        "Date: Mon, 01 Jan 2024 09:00:00 +0000",
        "Message-ID: <broken-boundary@example.org>",
        "MIME-Version: 1.0",
        "Content-Type: multipart/mixed; boundary=\"declared-boundary\"",
        "",
        "--different-boundary",
        "Content-Type: text/plain",
        "",
        "The body uses a boundary the header never declared.",
        "--different-boundary--",
    )

    /** A text part naming a charset the app can't know. */
    private fun unknownCharset(): ByteArray = rawMessage(
        "From: Bob <bob@example.org>",
        "To: Scenario User <user@example.org>",
        "Subject: $UNKNOWN_CHARSET_SUBJECT",
        "Date: Mon, 01 Jan 2024 09:01:00 +0000",
        "Message-ID: <unknown-charset@example.org>",
        "MIME-Version: 1.0",
        "Content-Type: text/plain; charset=x-not-a-real-charset",
        "Content-Transfer-Encoding: 8bit",
        "",
        "Raw bytes in a charset nobody knows: \u00e4\u00f6\u00fc\u00e9.",
    )

    /** A body containing NUL bytes. */
    private fun nulBytesInBody(): ByteArray = rawMessage(
        "From: Carol <carol@example.org>",
        "To: Scenario User <user@example.org>",
        "Subject: $NUL_BYTES_SUBJECT",
        "Date: Mon, 01 Jan 2024 09:02:00 +0000",
        "Message-ID: <nul-bytes@example.org>",
        "MIME-Version: 1.0",
        "Content-Type: text/plain; charset=utf-8",
        "",
        "before\u0000\u0000after",
    )

    /** Headers with unencoded 8-bit characters (a Latin-1 Subject and From display name). */
    private fun rawEightBitHeaders(): ByteArray = rawMessage(
        "From: Andr\u00e9 <andre@example.org>",
        "To: Scenario User <user@example.org>",
        "Subject: $EIGHT_BIT_SUBJECT",
        "Date: Mon, 01 Jan 2024 09:03:00 +0000",
        "Message-ID: <eight-bit@example.org>",
        "MIME-Version: 1.0",
        "Content-Type: text/plain; charset=iso-8859-1",
        "",
        "The headers above are not MIME encoded.",
    )

    /** No Date and no Message-ID headers at all. */
    private fun noDateOrMessageId(): ByteArray = rawMessage(
        "From: Dave <dave@example.org>",
        "To: Scenario User <user@example.org>",
        "Subject: $NO_DATE_SUBJECT",
        "MIME-Version: 1.0",
        "Content-Type: text/plain; charset=utf-8",
        "",
        "This message has neither a Date nor a Message-ID.",
    )

    /** A single header line well over the 998-octet limit (about 12 KB). */
    private fun veryLongHeaderLine(): ByteArray = rawMessage(
        "From: Erin <erin@example.org>",
        "To: Scenario User <user@example.org>",
        "Subject: $VERY_LONG_HEADER_SUBJECT",
        "Date: Mon, 01 Jan 2024 09:05:00 +0000",
        "Message-ID: <long-header@example.org>",
        "X-Filler: ${"a".repeat(12_000)}",
        "",
        "A body after a very long header line.",
    )

    /** Joins [lines] into an RFC 822 message, ending the header/body with CRLF, using Latin-1 for the raw bytes. */
    private fun rawMessage(vararg lines: String): ByteArray =
        (lines.joinToString("\r\n", postfix = "\r\n")).toByteArray(Charsets.ISO_8859_1)

    private companion object {
        const val BROKEN_BOUNDARY_SUBJECT = "Broken multipart boundary"
        const val UNKNOWN_CHARSET_SUBJECT = "Unknown charset body"
        const val NUL_BYTES_SUBJECT = "NUL bytes in body"
        const val NO_DATE_SUBJECT = "No date or message id"
        const val VERY_LONG_HEADER_SUBJECT = "Very long header line"
        const val WELL_FORMED_SUBJECT_1 = "Well formed one"
        const val WELL_FORMED_SUBJECT_2 = "Well formed two"

        // The fixture lookup subject for the 8-bit message; its displayed subject is decoded by the app.
        const val EIGHT_BIT_METADATA_SUBJECT = "Eight bit headers"

        // The raw, unencoded Latin-1 subject that goes on the wire for the 8-bit message.
        const val EIGHT_BIT_SUBJECT = "R\u00e9sum\u00e9 caf\u00e9 na\u00efve"

        // How the app may show it: each accented letter as one character of its choice.
        val EIGHT_BIT_SUBJECT_SHOWN = Regex("R.sum. caf. na.ve")

        val KNOWN_SUBJECTS = listOf(
            BROKEN_BOUNDARY_SUBJECT,
            UNKNOWN_CHARSET_SUBJECT,
            NUL_BYTES_SUBJECT,
            NO_DATE_SUBJECT,
            VERY_LONG_HEADER_SUBJECT,
            WELL_FORMED_SUBJECT_1,
            WELL_FORMED_SUBJECT_2,
        )
    }
}
