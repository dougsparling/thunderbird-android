package net.thunderbird.mail.testserver.provision

import java.net.URI
import java.net.URISyntaxException

private const val KIND = "testserver.kind"
private const val IMAP = "testserver.imap"
private const val ADMIN = "testserver.admin"
private const val DOMAIN = "testserver.domain"
private const val SMTP = "testserver.smtp"
private const val POP3 = "testserver.pop3"
private const val MAX_PORT = 65535
private val DOMAIN_PATTERN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

/**
 * Builds a [TestServerConfig] from `testserver.*` properties looked up with [property]. Collects every problem into
 * one [IllegalStateException] so a misconfigured run shows everything that's wrong at once.
 */
internal fun parseTestServerConfig(property: (String) -> String?): TestServerConfig {
    val errors = mutableListOf<String>()
    fun required(name: String): String? =
        property(name)?.trim()?.takeIf { it.isNotEmpty() } ?: null.also { errors += "$name is not set" }

    val kind = required(KIND)?.lowercase()
    val hostAndPort = required(IMAP)?.let { value ->
        parseHostAndPort(value) ?: null.also { errors += "$IMAP must be host:port, was '$value'" }
    }
    val domain = required(DOMAIN)?.lowercase()?.let { value ->
        value.takeIf { DOMAIN_PATTERN.matches(it) } ?: null.also { errors += "$DOMAIN is not a valid domain: '$value'" }
    }
    val smtp = parseOptionalEndpoint(SMTP, property, errors)
    val pop3 = parseOptionalEndpoint(POP3, property, errors)
    val adminUrl = property(ADMIN)?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
        value.takeIf { isHttpUrl(it) } ?: null.also { errors += "$ADMIN must be an http(s) URL, was '$value'" }
    }

    check(errors.isEmpty()) {
        "Invalid test mail server configuration: ${errors.joinToString("; ")}. " +
            "These system properties are normally set by the Gradle test mail server build service; " +
            "run the test through Gradle, or set them yourself (see TestServerConfig)."
    }

    return TestServerConfig(
        kind = checkNotNull(kind),
        imapHost = checkNotNull(hostAndPort).first,
        imapPort = hostAndPort.second,
        adminUrl = adminUrl?.trimEnd('/'),
        domain = checkNotNull(domain),
        smtp = smtp,
        pop3 = pop3,
    )
}

/** Reads the optional `host:port` property [name]; adds to [errors] and returns null if it's malformed. */
private fun parseOptionalEndpoint(
    name: String,
    property: (String) -> String?,
    errors: MutableList<String>,
): ServerEndpoint? {
    val value = property(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val hostAndPort = parseHostAndPort(value)
    if (hostAndPort == null) errors += "$name must be host:port, was '$value'"
    return hostAndPort?.let { (host, port) -> ServerEndpoint(host, port) }
}

/** Parses `host:port` or `[ipv6]:port`. */
private fun parseHostAndPort(value: String): Pair<String, Int>? {
    val separator = value.lastIndexOf(':')
    val host = value.substring(0, separator.coerceAtLeast(0)).removePrefix("[").removeSuffix("]")
    val port = value.substring(separator + 1).toIntOrNull()?.takeIf { it in 1..MAX_PORT }
    return if (host.isNotEmpty() && port != null) host to port else null
}

private fun isHttpUrl(value: String): Boolean =
    try {
        val uri = URI(value)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
    } catch (_: URISyntaxException) {
        false
    }
