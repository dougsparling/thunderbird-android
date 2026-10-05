package net.thunderbird.mail.testserver.fixture

import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@DslMarker
annotation class FixtureDsl

/**
 * Declares the server-side state of one mail user.
 *
 * The DSL does no I/O (apart from reading classpath resources for [FolderBuilder.eml]) and is fully deterministic:
 * the same input always produces identical [MessageFixture.rfc822] bytes.
 *
 * Folders are emitted in the order they are first declared, including folders without messages. Declaring the same
 * folder path more than once merges the declarations: messages are appended in declaration order, and a special use
 * given in one declaration applies to the folder. Two different special uses for the same folder are an error.
 *
 * Messages are numbered from [firstMessageNumber] for their default Message-ID and Date. Pass the next free number
 * when adding messages to a user that already has some, so the defaults stay unique.
 */
fun userFixture(
    password: String = "password",
    firstMessageNumber: Int = 1,
    block: UserFixtureBuilder.() -> Unit,
): UserFixture {
    require(firstMessageNumber >= 1) { "firstMessageNumber must be at least 1" }
    return UserFixtureBuilder(password, firstMessageNumber).apply(block).build()
}

/**
 * Defaults used for values the test doesn't declare.
 */
@OptIn(ExperimentalTime::class)
object FixtureDefaults {
    /**
     * Date of the first message of a user. Message `N` (1-based, counted per user in declaration order across all
     * folders) defaults to `BASE_DATE + (N - 1) minutes`, so messages without an explicit date sort in declaration
     * order.
     */
    val BASE_DATE: Instant = Instant.parse("2024-01-01T00:00:00Z")

    /**
     * Domain of generated Message-IDs, which have the form `<fixture-N@testserver.invalid>`.
     */
    const val MESSAGE_ID_DOMAIN = "testserver.invalid"

    /**
     * Sender of messages that don't declare a From.
     */
    const val SENDER = "Sender <sender@example.org>"

    internal fun dateFor(messageNumber: Int): Instant = BASE_DATE + (messageNumber - 1).minutes

    internal fun messageIdFor(messageNumber: Int): String = "<fixture-$messageNumber@$MESSAGE_ID_DOMAIN>"
}

@FixtureDsl
class UserFixtureBuilder internal constructor(private val password: String, firstMessageNumber: Int) {
    private val folders = LinkedHashMap<FolderPath, FolderState>()
    private var messageCounter = firstMessageNumber - 1

    /**
     * Declares messages in the inbox ([FolderPath.INBOX]).
     */
    fun inbox(block: FolderBuilder.() -> Unit = {}) {
        declareFolder(FolderPath.INBOX, specialUse = null, block)
    }

    /**
     * Declares a top-level folder. `folder("INBOX")` (any case) is the same as [inbox].
     */
    fun folder(name: String, specialUse: SpecialUse? = null, block: FolderBuilder.() -> Unit = {}) {
        validateFolderName(name)
        val path = if (name.equals(FolderPath.INBOX.segments.single(), ignoreCase = true)) {
            FolderPath.INBOX
        } else {
            FolderPath(listOf(name))
        }
        declareFolder(path, specialUse, block)
    }

    internal fun declareFolder(path: FolderPath, specialUse: SpecialUse?, block: FolderBuilder.() -> Unit) {
        require(!(path.isInbox && specialUse != null)) { "INBOX can't have a special use" }

        val state = folders.getOrPut(path) { FolderState(path) }
        state.mergeSpecialUse(specialUse)
        FolderBuilder(this, state).block()
    }

    internal fun nextMessageNumber(): Int = ++messageCounter

    internal fun build(): UserFixture {
        return UserFixture(
            password = password,
            folders = folders.values.map { it.toFolderFixture() },
        )
    }
}

@OptIn(ExperimentalTime::class)
@FixtureDsl
class FolderBuilder internal constructor(
    private val user: UserFixtureBuilder,
    private val state: FolderState,
) {
    val path: FolderPath get() = state.path

    /**
     * Declares a subfolder of this folder.
     */
    fun folder(name: String, specialUse: SpecialUse? = null, block: FolderBuilder.() -> Unit = {}) {
        validateFolderName(name)
        user.declareFolder(FolderPath(path.segments + name), specialUse, block)
    }

    /**
     * Declares a message built from the message DSL.
     */
    fun message(block: MessageBuilder.() -> Unit) {
        val messageNumber = user.nextMessageNumber()
        state.messages += MessageBuilder(messageNumber).apply(block).build()
    }

    /**
     * Declares a message with [subject]; [block] adds anything else. Shorthand for tests where only the subject
     * matters.
     */
    fun message(subject: String, block: MessageBuilder.() -> Unit = {}) {
        message {
            this.subject(subject)
            block()
        }
    }

    /**
     * Declares a message stored with exactly [bytes] as content. Nothing is validated or rewritten, so the content may
     * be deliberately malformed. [subject] and [messageId] are metadata for test lookups only.
     *
     * [internalDate] defaults to the same per-user default as the Date of DSL messages.
     */
    fun raw(
        bytes: ByteArray,
        subject: String? = null,
        messageId: String? = null,
        flags: Set<SystemFlag> = emptySet(),
        keywords: Set<String> = emptySet(),
        internalDate: Instant? = null,
    ) {
        keywords.forEach(::validateKeyword)
        val messageNumber = user.nextMessageNumber()
        state.messages += MessageFixture(
            rfc822 = bytes.copyOf(),
            subject = subject,
            messageId = messageId,
            flags = flags.toSet(),
            keywords = keywords.toSet(),
            internalDate = internalDate ?: FixtureDefaults.dateFor(messageNumber),
        )
    }

    /**
     * Like [raw], with the content read from the classpath resource at [resourcePath].
     */
    fun eml(
        resourcePath: String,
        subject: String? = null,
        messageId: String? = null,
        flags: Set<SystemFlag> = emptySet(),
        keywords: Set<String> = emptySet(),
        internalDate: Instant? = null,
    ) {
        raw(loadResource(resourcePath), subject, messageId, flags, keywords, internalDate)
    }

    private fun loadResource(resourcePath: String): ByteArray {
        val name = resourcePath.removePrefix("/")
        val classLoader = Thread.currentThread().contextClassLoader ?: FolderBuilder::class.java.classLoader
        val stream = requireNotNull(
            classLoader.getResourceAsStream(name) ?: FolderBuilder::class.java.classLoader.getResourceAsStream(name),
        ) { "Classpath resource not found: $resourcePath" }

        return stream.use { it.readBytes() }
    }
}

internal class FolderState(val path: FolderPath) {
    var specialUse: SpecialUse? = null
        private set
    val messages = mutableListOf<MessageFixture>()

    fun mergeSpecialUse(newSpecialUse: SpecialUse?) {
        if (newSpecialUse == null) return

        val current = specialUse
        require(current == null || current == newSpecialUse) {
            "Folder $path declared with conflicting special uses: $current and $newSpecialUse"
        }
        specialUse = newSpecialUse
    }

    fun toFolderFixture() = FolderFixture(path, specialUse, messages.toList())
}

private fun validateFolderName(name: String) {
    require(name.isNotEmpty()) { "Folder name must not be empty" }
    require('/' !in name) { "Folder name must not contain '/', nest folder { } blocks instead: $name" }
    require(name.none { it.isISOControl() }) { "Folder name must not contain control characters" }
}

private const val IMAP_ATOM_SPECIALS = "(){ %*\"\\]"

internal fun validateKeyword(keyword: String) {
    require(keyword.isNotEmpty()) { "Keyword must not be empty" }
    require(!keyword.startsWith('\\')) { "Keyword must not start with '\\', use SystemFlag instead: $keyword" }
    require(keyword.all { it.isVisibleAscii() && it !in IMAP_ATOM_SPECIALS }) {
        "Keyword must be an IMAP atom: $keyword"
    }
}
