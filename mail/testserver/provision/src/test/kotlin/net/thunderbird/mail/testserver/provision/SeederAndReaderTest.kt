package net.thunderbird.mail.testserver.provision

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import net.thunderbird.mail.testserver.fixture.FolderFixture
import net.thunderbird.mail.testserver.fixture.FolderPath
import net.thunderbird.mail.testserver.fixture.MessageFixture
import net.thunderbird.mail.testserver.fixture.SpecialUse
import net.thunderbird.mail.testserver.fixture.SystemFlag
import net.thunderbird.mail.testserver.fixture.UserFixture

@OptIn(ExperimentalTime::class)
class SeederAndReaderTest {
    private val servers = mutableListOf<FakeImapServer>()
    private val user = ProvisionedUser("alice@example.org", "secret")

    @AfterTest
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun server(delimiter: Char? = '/', capabilities: List<String> = listOf("IMAP4rev1")) =
        FakeImapServer(delimiter = delimiter, capabilities = capabilities).also {
            it.users[user.username] = user.password
            servers += it
        }

    private fun message(
        subject: String,
        flags: Set<SystemFlag> = emptySet(),
        keywords: Set<String> = emptySet(),
        date: String = "2024-03-05T10:15:30Z",
        rawSubject: String = subject,
    ) = MessageFixture(
        rfc822 = "Subject: $rawSubject\r\nMessage-ID: <$subject@example.org>\r\n\r\nBody of $subject\r\n".toByteArray(),
        subject = subject,
        messageId = "<$subject@example.org>",
        flags = flags,
        keywords = keywords,
        internalDate = Instant.parse(date),
    )

    private fun folder(
        vararg segments: String,
        specialUse: SpecialUse? = null,
        messages: List<MessageFixture> = emptyList(),
    ) =
        FolderFixture(FolderPath(segments.toList()), specialUse, messages)

    @Test
    fun `creates nested folders with parents first using a dot delimiter`() {
        // Arrange
        val server = server(delimiter = '.')
        val testSubject = DefaultImapSeeder(server.host, server.port)
        val fixture = UserFixture(user.password, listOf(folder("INBOX"), folder("Archive", "2024", "Q1")))

        // Act
        testSubject.seed(user, fixture)

        // Assert
        assertThat(server.commandsNamed("CREATE").map { it.args.single().text })
            .containsExactly("Archive", "Archive.2024", "Archive.2024.Q1")
    }

    @Test
    fun `creates nested folders with a slash delimiter and skips existing ones`() {
        // Arrange
        val server = server(delimiter = '/').apply { mailboxes["Archive"] = FakeMailbox() }
        val testSubject = DefaultImapSeeder(server.host, server.port)
        val fixture = UserFixture(user.password, listOf(folder("Archive", "2024"), folder("Archive")))

        // Act
        testSubject.seed(user, fixture)

        // Assert
        assertThat(server.commandsNamed("CREATE").map { it.args.single().text }).containsExactly("Archive/2024")
    }

    @Test
    fun `sets special use on create when the server supports CREATE-SPECIAL-USE, including declared parents`() {
        // Arrange
        val server = server(capabilities = listOf("IMAP4rev1", "CREATE-SPECIAL-USE"))
        val testSubject = DefaultImapSeeder(server.host, server.port)
        val fixture = UserFixture(
            user.password,
            listOf(folder("Archive", "2024"), folder("Archive", specialUse = SpecialUse.ARCHIVE)),
        )

        // Act
        testSubject.seed(user, fixture)

        // Assert
        assertThat(server.mailboxes["Archive"]!!.attributes).containsExactly("\\Archive")
        assertThat(server.mailboxes["Archive/2024"]!!.attributes).isEqualTo(emptyList())
    }

    @Test
    fun `skips special use without CREATE-SPECIAL-USE by default`() {
        // Arrange
        val server = server()
        val testSubject = DefaultImapSeeder(server.host, server.port)
        val fixture = UserFixture(user.password, listOf(folder("Sent", specialUse = SpecialUse.SENT)))

        // Act
        testSubject.seed(user, fixture)

        // Assert
        assertThat(server.commandsNamed("CREATE").single().args).containsExactly(FakeArg.Quoted("Sent"))
    }

    @Test
    fun `fails on special use without CREATE-SPECIAL-USE when asked to`() {
        // Arrange
        val server = server()
        val testSubject = DefaultImapSeeder(server.host, server.port, specialUseFallback = SpecialUseFallback.FAIL)
        val fixture = UserFixture(user.password, listOf(folder("Sent", specialUse = SpecialUse.SENT)))

        // Act & Assert
        assertFailure { testSubject.seed(user, fixture) }
            .isInstanceOf<IllegalStateException>()
            .messageContains("SPECIAL_USE_CREATE")
    }

    @Test
    fun `appends messages in declaration order with flags, keywords and internal date`() {
        // Arrange
        val server = server()
        val testSubject = DefaultImapSeeder(server.host, server.port)
        val fixture = UserFixture(
            user.password,
            listOf(
                folder(
                    "INBOX",
                    messages = listOf(
                        message(
                            "first",
                            flags = setOf(SystemFlag.SEEN, SystemFlag.FLAGGED),
                            keywords = setOf("\$label1"),
                        ),
                        message("second", date = "2023-12-24T23:59:01Z"),
                    ),
                ),
            ),
        )

        // Act
        testSubject.seed(user, fixture)

        // Assert
        val stored = server.mailboxes["INBOX"]!!.messages
        assertThat(stored.map { it.content.lines().first() }).containsExactly("Subject: first", "Subject: second")
        assertThat(stored[0].flags).containsExactly("\\Seen", "\\Flagged", "\$label1")
        assertThat(stored[0].internalDate).isEqualTo(" 5-Mar-2024 10:15:30 +0000")
        assertThat(stored[1].flags).isEqualTo(emptyList())
        assertThat(stored[1].internalDate).isEqualTo("24-Dec-2023 23:59:01 +0000")
    }

    @Test
    fun `reader returns seeded state using EXAMINE only`() {
        // Arrange
        val server = server(delimiter = '.')
        DefaultImapSeeder(server.host, server.port).seed(
            user,
            UserFixture(
                user.password,
                listOf(
                    folder("INBOX", messages = listOf(message("hello", flags = setOf(SystemFlag.SEEN)))),
                    folder(
                        "Archive",
                        "2024",
                        messages = listOf(
                            message("a", keywords = setOf("\$Forwarded")),
                            message("b", rawSubject = "=?UTF-8?Q?Gr=C3=BC=C3=9Fe?=", flags = setOf(SystemFlag.DELETED)),
                        ),
                    ),
                ),
            ),
        )
        server.commands.clear()
        val testSubject = DefaultServerStateReader(server.host, server.port)

        // Act
        val state = testSubject.read(user)

        // Assert
        assertThat(state.folders.map { it.path }).containsExactly(
            FolderPath.of("Archive"),
            FolderPath.of("Archive", "2024"),
            FolderPath.INBOX,
        )
        assertThat(state.folder(FolderPath.INBOX).message("hello")).isEqualTo(
            ServerMessageState(1, "hello", "<hello@example.org>", setOf(SystemFlag.SEEN), emptySet()),
        )
        val archive = state.folder(FolderPath.of("Archive", "2024"))
        assertThat(archive.messages.map { it.uid }).containsExactly(1L, 2L)
        assertThat(archive.message("a").keywords).isEqualTo(setOf("\$Forwarded"))
        assertThat(archive.message("Grüße").flags).isEqualTo(setOf(SystemFlag.DELETED))
        assertThat(server.commandNames()).doesNotContain("SELECT")
        assertThat(server.commandsNamed("UID FETCH").first().args.last().text)
            .isEqualTo("(UID FLAGS BODY.PEEK[HEADER.FIELDS (SUBJECT MESSAGE-ID)])")
    }

    @Test
    fun `reader decodes modified UTF-7 names sent as literals`() {
        // Arrange
        val server = FakeImapServer(listNamesAsLiteral = setOf("Entw&APw-rfe")).also {
            it.users[user.username] = user.password
            it.mailboxes["Entw&APw-rfe"] = FakeMailbox()
            servers += it
        }
        val testSubject = DefaultServerStateReader(server.host, server.port)

        // Act
        val state = testSubject.read(user)

        // Assert
        assertThat(state.folders.map { it.path }).containsExactly(FolderPath.of("Entwürfe"), FolderPath.INBOX)
        assertThat(server.commandsNamed("UID FETCH")).isEqualTo(emptyList())
    }
}
