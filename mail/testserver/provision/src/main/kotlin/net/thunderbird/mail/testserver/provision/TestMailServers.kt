package net.thunderbird.mail.testserver.provision

import java.security.SecureRandom
import java.util.Locale

/** Entry points for tests: pick a [TestMailServer] adapter by [TestServerConfig.kind] and build IMAP helpers. */
object TestMailServers {
    /** Supported values of `testserver.kind`. */
    val supportedKinds: Set<String> = setOf("james")

    fun fromConfig(config: TestServerConfig): TestMailServer = when (config.kind) {
        "james" -> JamesTestMailServer(config)
        else -> error("Unsupported testserver.kind '${config.kind}'; supported: ${supportedKinds.joinToString()}")
    }

    fun fromSystemProperties(): TestMailServer = fromConfig(TestServerConfig.fromSystemProperties())

    fun seeder(
        config: TestServerConfig,
        timeouts: ImapTimeouts = ImapTimeouts(),
        specialUseFallback: SpecialUseFallback = SpecialUseFallback.SKIP,
    ): ImapSeeder = DefaultImapSeeder(config.imapHost, config.imapPort, timeouts, specialUseFallback)

    fun stateReader(config: TestServerConfig, timeouts: ImapTimeouts = ImapTimeouts()): ServerStateReader =
        DefaultServerStateReader(config.imapHost, config.imapPort, timeouts)
}

private const val MAX_HINT_LENGTH = 40
private const val SUFFIX_BYTES = 4
private const val BYTE_MASK = 0xff
private val random = SecureRandom()
private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

/** A unique, lowercase login local part: the sanitised [nameHint] plus a random hex suffix, e.g. `sync-test-3fa9c2d1`. */
internal fun uniqueLocalPart(nameHint: String): String {
    val sanitized = nameHint.lowercase()
        .replace(NON_ALPHANUMERIC, "-")
        .trim('-')
        .take(MAX_HINT_LENGTH)
        .trimEnd('-')
        .ifEmpty { "user" }
    val suffix = ByteArray(SUFFIX_BYTES).also(random::nextBytes).joinToString("") {
        "%02x".format(Locale.ROOT, it.toInt() and BYTE_MASK)
    }
    return "$sanitized-$suffix"
}

/**
 * Maps the post-login IMAP capabilities to [ServerCapability], using a throwaway user created and deleted on [server].
 * Capabilities before login are often incomplete, which is why this logs in.
 */
internal fun probeServerCapabilities(server: TestMailServer, timeouts: ImapTimeouts): Set<ServerCapability> {
    val password = uniqueLocalPart("probe-password")
    val user = server.createUser("capability-probe", password)
    try {
        val imapCapabilities = openLoggedInSession(server.config.imapHost, server.config.imapPort, timeouts, user)
            .use { it.capabilities }
        return mapImapCapabilities(imapCapabilities)
    } finally {
        server.deleteUser(user)
    }
}

internal fun mapImapCapabilities(imapCapabilities: Set<String>): Set<ServerCapability> = buildSet {
    val upper = imapCapabilities.map { it.uppercase() }.toSet()
    if ("CREATE-SPECIAL-USE" in upper) add(ServerCapability.SPECIAL_USE_CREATE)
    if ("CONDSTORE" in upper || "QRESYNC" in upper) add(ServerCapability.CONDSTORE)
    if ("MOVE" in upper) add(ServerCapability.MOVE)
    if ("IDLE" in upper) add(ServerCapability.IDLE)
    if ("UIDPLUS" in upper) add(ServerCapability.UIDPLUS)
}

/** Percent-encodes a URL path segment, keeping unreserved characters and `@` as they are. */
internal fun encodePathSegment(segment: String): String = buildString {
    for (byte in segment.toByteArray(Charsets.UTF_8)) {
        val c = (byte.toInt() and BYTE_MASK).toChar()
        val unreserved = (c.isLetterOrDigit() && c.code < ASCII_LIMIT) || c in "-._~@"
        if (unreserved) append(c) else append("%%%02X".format(Locale.ROOT, c.code))
    }
}

/** Serializes [value] as a JSON string literal. */
internal fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(Locale.ROOT, c.code))
            else -> append(c)
        }
    }
    append('"')
}

private const val ASCII_LIMIT = 0x80
