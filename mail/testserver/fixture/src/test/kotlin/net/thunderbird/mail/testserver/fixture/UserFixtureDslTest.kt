package net.thunderbird.mail.testserver.fixture

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.messageContains
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class UserFixtureDslTest {

    @Test
    fun `should build nested folder paths in declaration order`() {
        val testSubject = userFixture(password = "secret") {
            inbox()
            folder("Archive", specialUse = SpecialUse.ARCHIVE) {
                folder("2024") {
                    folder("March")
                }
            }
            folder("Weird")
        }

        assertThat(testSubject.password).isEqualTo("secret")
        assertThat(testSubject.folders.map { it.path }).containsExactly(
            FolderPath.INBOX,
            FolderPath.of("Archive"),
            FolderPath.of("Archive", "2024"),
            FolderPath.of("Archive", "2024", "March"),
            FolderPath.of("Weird"),
        )
        assertThat(testSubject.folders.map { it.specialUse }).containsExactly(
            null,
            SpecialUse.ARCHIVE,
            null,
            null,
            null,
        )
    }

    @Test
    fun `should use default password`() {
        val testSubject = userFixture { }

        assertThat(testSubject.password).isEqualTo("password")
        assertThat(testSubject.folders).isEmpty()
    }

    @Test
    fun `should emit folders without messages`() {
        val testSubject = userFixture {
            folder("Empty")
        }

        assertThat(testSubject.folders.single().messages).isEmpty()
    }

    @Test
    fun `should treat top-level INBOX folder in any case as the inbox`() {
        val testSubject = userFixture {
            folder("inbox") {
                message { subject("first") }
            }
            inbox {
                message { subject("second") }
                folder("Sub")
            }
        }

        assertThat(testSubject.folders.map { it.path }).containsExactly(
            FolderPath.INBOX,
            FolderPath.of("INBOX", "Sub"),
        )
        assertThat(testSubject.folders.first().messages.map { it.subject }).containsExactly("first", "second")
    }

    @Test
    fun `should merge repeated folder declarations`() {
        val testSubject = userFixture {
            folder("Archive") {
                message { subject("one") }
            }
            folder("Other")
            folder("Archive", specialUse = SpecialUse.ARCHIVE) {
                message { subject("two") }
            }
        }

        assertThat(
            testSubject.folders.map {
                it.path
            },
        ).containsExactly(FolderPath.of("Archive"), FolderPath.of("Other"))
        val archive = testSubject.folders.first()
        assertThat(archive.specialUse).isEqualTo(SpecialUse.ARCHIVE)
        assertThat(archive.messages.map { it.subject }).containsExactly("one", "two")
    }

    @Test
    fun `should fail on conflicting special use`() {
        assertFailure {
            userFixture {
                folder("Stuff", specialUse = SpecialUse.SENT)
                folder("Stuff", specialUse = SpecialUse.TRASH)
            }
        }.isInstanceOf<IllegalArgumentException>()
            .hasMessage("Folder Stuff declared with conflicting special uses: SENT and TRASH")
    }

    @Test
    fun `should reject folder names containing a slash`() {
        assertFailure {
            userFixture { folder("Archive/2024") }
        }.isInstanceOf<IllegalArgumentException>().messageContains("nest folder")
    }

    @Test
    fun `should reject special use on inbox`() {
        assertFailure {
            userFixture { folder("INBOX", specialUse = SpecialUse.ARCHIVE) }
        }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `should number default dates and message ids per user across folders`() {
        val testSubject = userFixture {
            inbox {
                message { }
            }
            folder("Other") {
                raw(byteArrayOf())
                message { }
            }
        }

        val messages = testSubject.folders.flatMap { it.messages }
        assertThat(messages.map { it.messageId }).containsExactly(
            "<fixture-1@testserver.invalid>",
            null,
            "<fixture-3@testserver.invalid>",
        )
        assertThat(messages.map { it.internalDate }).containsExactly(
            FixtureDefaults.BASE_DATE,
            FixtureDefaults.BASE_DATE + 1.minutes,
            FixtureDefaults.BASE_DATE + 2.minutes,
        )
        assertThat(messages.first().rfc822.decodeToString()).contains(
            "Date: Mon, 1 Jan 2024 00:00:00 +0000\r\n",
        )
    }

    @Test
    fun `should continue default numbering from firstMessageNumber`() {
        val testSubject = userFixture(firstMessageNumber = 3) {
            inbox {
                message { }
            }
        }

        val message = testSubject.folders.single().messages.single()
        assertThat(message.messageId).isEqualTo("<fixture-3@testserver.invalid>")
        assertThat(message.internalDate).isEqualTo(FixtureDefaults.BASE_DATE + 2.minutes)
    }

    @Test
    fun `should reject firstMessageNumber below one`() {
        assertFailure {
            userFixture(firstMessageNumber = 0) { }
        }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `should default subject to null and internal date to message date`() {
        val date = Instant.parse("2024-03-01T10:00:00Z")

        val testSubject = userFixture {
            inbox {
                message { date(date) }
            }
        }

        val message = testSubject.folders.single().messages.single()
        assertThat(message.subject).isNull()
        assertThat(message.internalDate).isEqualTo(date)
    }

    @Test
    fun `should keep explicit metadata`() {
        val internalDate = Instant.parse("2020-05-05T05:05:05Z")

        val testSubject = userFixture {
            inbox {
                message {
                    subject("Hello")
                    messageId("custom@example.org")
                    internalDate(internalDate)
                    flags(SystemFlag.SEEN)
                    flags(SystemFlag.FLAGGED)
                    keywords("\$Forwarded", "\$Label1")
                }
            }
        }

        val message = testSubject.folders.single().messages.single()
        assertThat(message.subject).isEqualTo("Hello")
        assertThat(message.messageId).isEqualTo("<custom@example.org>")
        assertThat(message.internalDate).isEqualTo(internalDate)
        assertThat(message.flags).isEqualTo(setOf(SystemFlag.SEEN, SystemFlag.FLAGGED))
        assertThat(message.keywords).isEqualTo(setOf("\$Forwarded", "\$Label1"))
    }

    @Test
    fun `should reject invalid keywords`() {
        assertFailure {
            userFixture { inbox { message { keywords("\\Seen") } } }
        }.isInstanceOf<IllegalArgumentException>()

        assertFailure {
            userFixture { inbox { message { keywords("two words") } } }
        }.isInstanceOf<IllegalArgumentException>()
    }
}
