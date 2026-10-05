package net.thunderbird.mail.testserver.provision

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * [TestMailServer] for Apache James, managing users through the WebAdmin REST API at [TestServerConfig.adminUrl].
 *
 * Assumes James runs with virtual hosting enabled, so logins are full `user@domain` addresses.
 * [capabilities] come from the server's post-login IMAP CAPABILITY response, probed once with a throwaway user.
 */
class JamesTestMailServer(
    override val config: TestServerConfig,
    private val imapTimeouts: ImapTimeouts = ImapTimeouts(),
    private val httpTimeout: Duration = 30.seconds,
) : TestMailServer {
    private val adminUrl: String = requireNotNull(config.adminUrl) {
        "James needs its WebAdmin URL; set testserver.admin"
    }.trimEnd('/')

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(httpTimeout.toJavaDuration())
        .build()

    private val domainLock = Any()

    @Volatile
    private var domainEnsured = false

    override val capabilities: Set<ServerCapability> by lazy {
        probeServerCapabilities(this, imapTimeouts)
    }

    override fun createUser(nameHint: String, password: String): ProvisionedUser {
        ensureDomain()
        val username = "${uniqueLocalPart(nameHint)}@${config.domain}"
        val path = "/users/${encodePathSegment(username)}"
        repeat(MAX_CREATE_ATTEMPTS) {
            // TODO(verify against James): PUT /users/{user} with {"password": "..."} answers 204.
            send("PUT", path, body = """{"password":${jsonString(password)}}""")
            // James' in-memory user store sometimes loses one of two users created at the same moment (seen with
            // scenario test JVMs starting in parallel), so only hand out a user the server still knows.
            if (send("HEAD", path, acceptNotFound = true) != HTTP_NOT_FOUND) return ProvisionedUser(username, password)
        }
        throw IOException("James WebAdmin lost user $username $MAX_CREATE_ATTEMPTS times after creating it")
    }

    override fun deleteUser(user: ProvisionedUser) {
        val path = "/users/${encodePathSegment(user.username)}"
        // Deleting a James user keeps its mailboxes, so remove them first to keep the server small.
        // TODO(verify against James): DELETE /users/{user}/mailboxes removes all of a user's mailboxes.
        send("DELETE", "$path/mailboxes", acceptNotFound = true)
        // TODO(verify against James): DELETE /users/{user} answers 204 whether or not the user exists.
        send("DELETE", path, acceptNotFound = true)
    }

    private fun ensureDomain() {
        if (domainEnsured) return
        synchronized(domainLock) {
            if (!domainEnsured) {
                // TODO(verify against James): PUT /domains/{domain} is idempotent and answers 204.
                send("PUT", "/domains/${encodePathSegment(config.domain)}")
                domainEnsured = true
            }
        }
    }

    /** Sends a WebAdmin request and returns the HTTP status; fails unless it's a success (or 404, if accepted). */
    private fun send(method: String, path: String, body: String? = null, acceptNotFound: Boolean = false): Int {
        val request = HttpRequest.newBuilder(URI.create(adminUrl + path))
            .timeout(httpTimeout.toJavaDuration())
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .apply { if (body != null) header("Content-Type", "application/json") }
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted during James WebAdmin $method", e)
        }
        val status = response.statusCode()
        val ok = status in HTTP_SUCCESS || (acceptNotFound && status == HTTP_NOT_FOUND)
        if (!ok) {
            // The request body may hold a password, so only the method, path and response are reported.
            throw IOException(
                "James WebAdmin $method $path failed: HTTP $status ${response.body().take(MAX_ERROR_BODY)}",
            )
        }
        return status
    }

    private companion object {
        val HTTP_SUCCESS = 200..299
        const val HTTP_NOT_FOUND = 404
        const val MAX_ERROR_BODY = 500
        const val MAX_CREATE_ATTEMPTS = 5
    }
}
