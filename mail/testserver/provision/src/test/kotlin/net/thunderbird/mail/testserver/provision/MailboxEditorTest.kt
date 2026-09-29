package net.thunderbird.mail.testserver.provision

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.messageContains
import kotlin.test.AfterTest
import kotlin.test.Test
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.SystemFlag

class MailboxEditorTest {
    private val servers = mutableListOf<FakeImapServer>()
    private val user = ProvisionedUser("alice@example.org", "secret")

    @AfterTest
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun server(
        delimiter: Char? = '/',
        capabilities: List<String> = listOf("IMAP4rev1"),
    ) = FakeImapServer(delimiter = delimiter, capabilities = capabilities).also {
        it.users[user.username] = user.password
        servers += it
    }

    private fun FakeImapServer.addMessage(mailbox: String, subject: String, flags: List<String> = emptyList()) {
        mailboxes.getOrPut(mailbox) { FakeMailbox() }
            .add(FakeMessage(0, flags, null, "Subject: $subject\r\nMessage-ID: <$subject@example.org>\r\n\r\nBody\r\n"))
    }

    private fun FakeImapServer.subjects(mailbox: String) =
        mailboxes.getValue(mailbox).messages.map { it.content.lines().first().removePrefix("Subject: ") }

    @Test
    fun `deleteMessage flags the message and expunges only it with UIDPLUS`() {
        // Arrange
        val server = server(capabilities = listOf("IMAP4rev1", "UIDPLUS"))
        server.addMessage("INBOX", "keep", flags = listOf("\\Deleted"))
        server.addMessage("INBOX", "remove")
        val testSubject = DefaultMailboxEditor(server.host, server.port)

        // Act
        testSubject.deleteMessage(user, FolderPath.INBOX, "remove")

        // Assert
        assertThat(server.subjects("INBOX")).containsExactly("keep")
        assertThat(server.commandsNamed("UID EXPUNGE").single().args.single().text).isEqualTo("2")
        assertThat(server.commandNames()).doesNotContain("EXAMINE")
    }

    @Test
    fun `deleteMessage without expunge only flags the message`() {
        // Arrange
        val server = server()
        server.addMessage("INBOX", "remove")
        val testSubject = DefaultMailboxEditor(server.host, server.port)

        // Act
        testSubject.deleteMessage(user, FolderPath.INBOX, "remove", expunge = false)

        // Assert
        assertThat(server.mailboxes.getValue("INBOX").messages.single().flags).containsExactly("\\Deleted")
        assertThat(server.commandNames()).doesNotContain("EXPUNGE")
    }

    @Test
    fun `deleteMessage falls back to EXPUNGE without UIDPLUS`() {
        // Arrange
        val server = server()
        server.addMessage("INBOX", "remove")
        val testSubject = DefaultMailboxEditor(server.host, server.port)

        // Act
        testSubject.deleteMessage(user, FolderPath.INBOX, "remove")

        // Assert
        assertThat(server.subjects("INBOX")).isEmpty()
        assertThat(server.commandNames()).doesNotContain("UID EXPUNGE")
    }

    @Test
    fun `moveMessage uses UID MOVE when supported`() {
        // Arrange
        val server = server(delimiter = '.', capabilities = listOf("IMAP4rev1", "MOVE"))
        server.addMessage("INBOX", "move me")
        server.mailboxes["Archive.2024"] = FakeMailbox()
        val testSubject = DefaultMailboxEditor(server.host, server.port)

        // Act
        testSubject.moveMessage(user, FolderPath.INBOX, "move me", FolderPath.of("Archive", "2024"))

        // Assert
        assertThat(server.subjects("INBOX")).isEmpty()
        assertThat(server.subjects("Archive.2024")).containsExactly("move me")
        assertThat(server.commandsNamed("UID MOVE").single().args.map { it.text }).containsExactly("1", "Archive.2024")
    }

    @Test
    fun `moveMessage copies, deletes and expunges without MOVE`() {
        // Arrange
        val server = server()
        server.addMessage("INBOX", "move me")
        server.mailboxes["Archive"] = FakeMailbox()
        val testSubject = DefaultMailboxEditor(server.host, server.port)

        // Act
        testSubject.moveMessage(user, FolderPath.INBOX, "move me", FolderPath.of("Archive"))

        // Assert
        assertThat(server.subjects("INBOX")).isEmpty()
        assertThat(server.subjects("Archive")).containsExactly("move me")
        assertThat(server.commandNames()).containsExactly(
            "LOGIN",
            "CAPABILITY",
            "LIST",
            "SELECT",
            "UID FETCH",
            "UID COPY",
            "UID STORE",
            "EXPUNGE",
            "LOGOUT",
        )
    }

    @Test
    fun `setFlags adds and removes system flags silently`() {
        // Arrange
        val server = server()
        server.addMessage("INBOX", "flag me", flags = listOf("\\Seen"))
        val testSubject = DefaultMailboxEditor(server.host, server.port)

        // Act
        testSubject.setFlags(
            user,
            FolderPath.INBOX,
            "flag me",
            add = setOf(SystemFlag.FLAGGED),
            remove = setOf(SystemFlag.SEEN),
        )

        // Assert
        assertThat(server.mailboxes.getValue("INBOX").messages.single().flags).containsExactly("\\Flagged")
        assertThat(server.commandsNamed("UID STORE").map { it.args[1].text })
            .containsExactly("+FLAGS.SILENT", "-FLAGS.SILENT")
    }

    @Test
    fun `a subject that doesn't identify one message fails without changing anything`() {
        // Arrange
        val server = server()
        server.addMessage("INBOX", "twin")
        server.addMessage("INBOX", "twin")
        val testSubject = DefaultMailboxEditor(server.host, server.port)

        // Act & Assert
        assertFailure { testSubject.deleteMessage(user, FolderPath.INBOX, "twin") }
            .isInstanceOf<IllegalStateException>()
            .messageContains("Expected one message with subject 'twin' in INBOX on the server, found 2")
        assertFailure { testSubject.deleteMessage(user, FolderPath.INBOX, "missing") }
            .messageContains("found 0")
        assertThat(server.commandNames()).doesNotContain("UID STORE")
    }

    @Test
    fun `deleteFolder and renameFolder use the server's delimiter`() {
        // Arrange
        val server = server(delimiter = '.')
        server.mailboxes["Old.Sub"] = FakeMailbox()
        server.mailboxes["Gone"] = FakeMailbox()
        val testSubject = DefaultMailboxEditor(server.host, server.port)

        // Act
        testSubject.renameFolder(user, FolderPath.of("Old", "Sub"), FolderPath.of("New"))
        testSubject.deleteFolder(user, FolderPath.of("Gone"))

        // Assert
        assertThat(server.mailboxes.keys.toList()).containsExactly("INBOX", "New")
    }

    @Test
    fun `reader reports UIDVALIDITY, which changes when a folder is created again`() {
        // Arrange
        val server = server()
        server.mailboxes["Folder"] = FakeMailbox()
        val reader = DefaultServerStateReader(server.host, server.port)
        val before = reader.read(user).folder(FolderPath.of("Folder")).uidValidity
        val testSubject = DefaultMailboxEditor(server.host, server.port)

        // Act
        testSubject.deleteFolder(user, FolderPath.of("Folder"))
        server.mailboxes["Folder"] = FakeMailbox()
        val after = reader.read(user).folder(FolderPath.of("Folder")).uidValidity

        // Assert
        assertThat(before).isNotEqualTo(null)
        assertThat(after).isNotEqualTo(before)
    }
}
