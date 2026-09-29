package net.thunderbird.mail.testserver.provision

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import assertk.assertions.prop
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class ImapSessionTest {
    private val servers = mutableListOf<FakeImapServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun server(
        capabilities: List<String> = listOf("IMAP4rev1"),
        capabilityInGreeting: Boolean = true,
    ) = FakeImapServer(capabilities = capabilities, capabilityInGreeting = capabilityInGreeting)
        .also { servers += it }

    private fun FakeImapServer.connect() = ImapSession(ImapConnection.connect(host, port))

    @Test
    fun `login quotes passwords containing quotes and backslashes`() {
        // Arrange
        val password = "pa\"ss\\word"
        val server = server().apply { users["user@example.org"] = password }
        val testSubject = server.connect()

        // Act
        testSubject.use { it.login("user@example.org", password) }

        // Assert
        assertThat(server.commandsNamed("LOGIN").single().args).containsExactly(
            FakeArg.Quoted("user@example.org"),
            FakeArg.Quoted(password),
        )
    }

    @Test
    fun `login sends non-ASCII passwords as a synchronizing literal`() {
        // Arrange
        val password = "pässwörd"
        val server = server().apply { users["user"] = password }
        val testSubject = server.connect()

        // Act
        testSubject.use { it.login("user", password) }

        // Assert
        val login = server.commandsNamed("LOGIN").single()
        assertThat(login.args[1]).isEqualTo(FakeArg.Literal(password, nonSynchronizing = false))
        assertThat(login.continuationsSent).isEqualTo(1)
    }

    @Test
    fun `failed login names the command but not the credentials`() {
        // Arrange
        val server = server().apply { users["user"] = "right" }
        val testSubject = server.connect()

        // Act & Assert
        assertFailure { testSubject.use { it.login("user", "wrong-secret") } }
            .isInstanceOf<ImapCommandException>()
            .all {
                prop(ImapCommandException::command).isEqualTo("LOGIN")
                prop(ImapCommandException::status).isEqualTo("NO")
                messageContains("IMAP LOGIN failed: NO [AUTHENTICATIONFAILED]")
                transform { it.message.orEmpty() }.doesNotContain("wrong-secret")
            }
    }

    @Test
    fun `capabilities are fetched when the greeting has none and refreshed after login`() {
        // Arrange
        val server = FakeImapServer(
            capabilities = listOf("IMAP4rev1"),
            postLoginCapabilities = listOf("IMAP4rev1", "CREATE-SPECIAL-USE", "MOVE"),
            capabilityInGreeting = false,
        ).also { servers += it }
        server.users["u"] = "p"
        val testSubject = server.connect()

        // Act
        val before = testSubject.capabilities
        testSubject.login("u", "p")
        val after = testSubject.capabilities
        testSubject.close()

        // Assert
        assertThat(before).isEqualTo(setOf("IMAP4REV1"))
        assertThat(after).isEqualTo(setOf("IMAP4REV1", "CREATE-SPECIAL-USE", "MOVE"))
    }

    @Test
    fun `append uses a synchronizing literal without LITERAL+`() {
        // Arrange
        val server = server().apply { users["u"] = "p" }
        val testSubject = server.connect().apply { login("u", "p") }

        // Act
        testSubject.use {
            it.append("INBOX", listOf("\\Seen"), " 5-Mar-2024 10:15:30 +0000", "Subject: x\r\n\r\n".toByteArray())
        }

        // Assert
        val append = server.commandsNamed("APPEND").single()
        assertThat(append.continuationsSent).isEqualTo(1)
        assertThat(append.args).containsExactly(
            FakeArg.Atom("INBOX"),
            FakeArg.Group(listOf(FakeArg.Atom("\\Seen"))),
            FakeArg.Quoted(" 5-Mar-2024 10:15:30 +0000"),
            FakeArg.Literal("Subject: x\r\n\r\n", nonSynchronizing = false),
        )
    }

    @Test
    fun `append uses a non-synchronizing literal with LITERAL+`() {
        // Arrange
        val server = server(capabilities = listOf("IMAP4rev1", "LITERAL+")).apply { users["u"] = "p" }
        val testSubject = server.connect().apply { login("u", "p") }

        // Act
        testSubject.use { it.append("INBOX", emptyList(), null, "Subject: y\r\n\r\nbody".toByteArray()) }

        // Assert
        val append = server.commandsNamed("APPEND").single()
        assertThat(append.continuationsSent).isEqualTo(0)
        assertThat(append.args).containsExactly(
            FakeArg.Atom("INBOX"),
            FakeArg.Literal("Subject: y\r\n\r\nbody", nonSynchronizing = true),
        )
    }

    @Test
    fun `tagged NO and BAD become exceptions naming the command and folder`() {
        // Arrange
        val server = server().apply {
            users["u"] = "p"
            failures["CREATE"] = "NO [CANNOT] Not allowed"
            failures["EXAMINE"] = "BAD Syntax"
        }
        val testSubject = server.connect().apply { login("u", "p") }

        // Act & Assert
        assertFailure { testSubject.create("Stuff", null) }
            .isInstanceOf<ImapCommandException>()
            .hasMessage("IMAP CREATE (Stuff) failed: NO [CANNOT] Not allowed")
        assertFailure { testSubject.examine("Stuff") }
            .isInstanceOf<ImapCommandException>()
            .hasMessage("IMAP EXAMINE (Stuff) failed: BAD Syntax")
        testSubject.close()
    }

    @Test
    fun `mailbox names are sent in modified UTF-7 and decoded from LIST`() {
        // Arrange
        val server = server().apply { users["u"] = "p" }
        val testSubject = server.connect().apply { login("u", "p") }

        // Act
        testSubject.create("Entwürfe", null)
        val names = testSubject.list().map { it.name }
        testSubject.close()

        // Assert
        assertThat(server.commandsNamed("CREATE").single().args).containsExactly(FakeArg.Quoted("Entw&APw-rfe"))
        assertThat(names).containsExactly("INBOX", "Entwürfe")
    }

    @Test
    fun `a server that never greets fails with a timeout instead of hanging`() {
        // Arrange
        val server = FakeImapServer(sendGreeting = false).also { servers += it }

        // Act & Assert
        assertFailure { ImapConnection.connect(server.host, server.port, ImapTimeouts(read = 200.milliseconds)) }
            .isInstanceOf<SocketTimeoutException>()
    }

    @Test
    fun `plaintext login is refused when the server advertises LOGINDISABLED`() {
        // Arrange
        val server = server(capabilities = listOf("IMAP4rev1", "LOGINDISABLED"))
        val testSubject = server.connect()

        // Act & Assert
        assertFailure { testSubject.use { it.login("u", "p") } }
            .isInstanceOf<ImapProtocolException>()
            .messageContains("LOGINDISABLED")
        assertThat(server.commandNames()).contains("LOGOUT")
    }
}

private fun assertk.Assert<Throwable>.hasMessage(expected: String) =
    transform { it.message }.isEqualTo(expected)
