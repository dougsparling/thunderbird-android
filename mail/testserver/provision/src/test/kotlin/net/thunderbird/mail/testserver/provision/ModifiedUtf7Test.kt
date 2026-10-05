package net.thunderbird.mail.testserver.provision

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class ModifiedUtf7Test {
    private val testSubject = ModifiedUtf7

    @Test
    fun `encodes the RFC 3501 example`() {
        // Act
        val encoded = testSubject.encode("~peter/mail/台北/日本語")

        // Assert
        assertThat(encoded).isEqualTo("~peter/mail/&U,BTFw-/&ZeVnLIqe-")
    }

    @Test
    fun `encodes ampersand and non-ASCII runs`() {
        // Act
        val encoded = testSubject.encode("R&D Entwürfe ☃&")

        // Assert
        assertThat(encoded).isEqualTo("R&-D Entw&APw-rfe &JgM-&-")
    }

    @Test
    fun `decode reverses encode`() {
        // Arrange
        val names = listOf("INBOX", "R&D", "Entwürfe", "日本語/台北", "emoji 📬 box", "a&b&c")

        // Act
        val roundTripped = names.map { testSubject.decode(testSubject.encode(it)) }

        // Assert
        assertThat(roundTripped).isEqualTo(names)
    }
}
