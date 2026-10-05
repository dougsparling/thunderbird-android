package net.thunderbird.mail.testserver.provision

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import kotlin.test.Test

class TestServerConfigTest {

    @Test
    fun `parses all properties`() {
        // Arrange
        val properties = mapOf(
            "testserver.kind" to "James",
            "testserver.imap" to "localhost:1143",
            "testserver.admin" to "http://localhost:8000/",
            "testserver.domain" to "Example.org",
            "testserver.smtp" to "localhost:1025",
            "testserver.pop3" to "localhost:1110",
        )

        // Act
        val config = parseTestServerConfig(properties::get)

        // Assert
        assertThat(config).isEqualTo(
            TestServerConfig(
                kind = "james",
                imapHost = "localhost",
                imapPort = 1143,
                adminUrl = "http://localhost:8000",
                domain = "example.org",
                smtp = ServerEndpoint("localhost", 1025),
                pop3 = ServerEndpoint("localhost", 1110),
            ),
        )
    }

    @Test
    fun `admin URL is optional and IPv6 hosts are supported`() {
        // Arrange
        val properties = mapOf(
            "testserver.kind" to "dovecot",
            "testserver.imap" to "[::1]:143",
            "testserver.domain" to "example.org",
        )

        // Act
        val config = parseTestServerConfig(properties::get)

        // Assert
        assertThat(config.imapHost).isEqualTo("::1")
        assertThat(config.imapPort).isEqualTo(143)
        assertThat(config.adminUrl).isEqualTo(null)
        assertThat(config.smtp).isEqualTo(null)
        assertThat(config.pop3).isEqualTo(null)
    }

    @Test
    fun `reports every missing or invalid property at once`() {
        // Arrange
        val properties = mapOf(
            "testserver.imap" to "localhost",
            "testserver.admin" to "ftp://x",
            "testserver.smtp" to "nowhere",
        )

        // Act & Assert
        assertFailure { parseTestServerConfig(properties::get) }
            .isInstanceOf<IllegalStateException>()
            .transform { it.message.orEmpty() }
            .all {
                contains("testserver.kind is not set")
                contains("testserver.imap must be host:port, was 'localhost'")
                contains("testserver.domain is not set")
                contains("testserver.admin must be an http(s) URL")
                contains("testserver.smtp must be host:port, was 'nowhere'")
                contains("Gradle")
            }
    }

    @Test
    fun `rejects an out of range port`() {
        // Arrange
        val properties = mapOf(
            "testserver.kind" to "james",
            "testserver.imap" to "localhost:70000",
            "testserver.domain" to "example.org",
        )

        // Act & Assert
        assertFailure { parseTestServerConfig(properties::get) }.messageContains("host:port")
    }

    @Test
    fun `factory rejects unknown kinds`() {
        // Arrange
        val config = TestServerConfig("exchange", "localhost", 143, null, "example.org")

        // Act & Assert
        assertFailure { TestMailServers.fromConfig(config) }
            .hasMessage("Unsupported testserver.kind 'exchange'; supported: james")
    }
}
