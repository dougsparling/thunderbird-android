package net.thunderbird.mail.testserver.provision

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.matches
import assertk.assertions.messageContains
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test

class JamesTestMailServerTest {
    private val imapServer = FakeImapServer(postLoginCapabilities = listOf("IMAP4rev1", "MOVE", "UIDPLUS", "IDLE"))
    private val webAdmin = FakeWebAdmin(imapServer)
    private val config = TestServerConfig(
        kind = "james",
        imapHost = imapServer.host,
        imapPort = imapServer.port,
        adminUrl = webAdmin.url,
        domain = "example.org",
    )

    @AfterTest
    fun tearDown() {
        webAdmin.close()
        imapServer.close()
    }

    @Test
    fun `createUser ensures the domain once and creates a unique user with a JSON password`() {
        // Arrange
        val testSubject = JamesTestMailServer(config)

        // Act
        val first = testSubject.createUser("SyncTest", "p\"w")
        val second = testSubject.createUser("SyncTest", "pw2")

        // Assert
        assertThat(first.username).matches(Regex("synctest-[0-9a-f]{8}@example\\.org"))
        assertThat(first.password).isEqualTo("p\"w")
        assertThat(first.username == second.username).isEqualTo(false)
        assertThat(webAdmin.requests).containsExactly(
            FakeWebAdmin.Request("PUT", "/domains/example.org", ""),
            FakeWebAdmin.Request("PUT", "/users/${first.username}", "{\"password\":\"p\\\"w\"}"),
            FakeWebAdmin.Request("HEAD", "/users/${first.username}", ""),
            FakeWebAdmin.Request("PUT", "/users/${second.username}", "{\"password\":\"pw2\"}"),
            FakeWebAdmin.Request("HEAD", "/users/${second.username}", ""),
        )
    }

    @Test
    fun `createUser creates the user again when the server lost it`() {
        // Arrange
        webAdmin.loseUserCreations = 2
        val testSubject = JamesTestMailServer(config)

        // Act
        val user = testSubject.createUser("SyncTest", "pw")

        // Assert
        assertThat(webAdmin.requests.map { "${it.method} ${it.path}" }).containsExactly(
            "PUT /domains/example.org",
            "PUT /users/${user.username}",
            "HEAD /users/${user.username}",
            "PUT /users/${user.username}",
            "HEAD /users/${user.username}",
            "PUT /users/${user.username}",
            "HEAD /users/${user.username}",
        )
        assertThat(imapServer.users[user.username]).isEqualTo("pw")
    }

    @Test
    fun `deleteUser removes mailboxes and the user, tolerating missing ones`() {
        // Arrange
        val testSubject = JamesTestMailServer(config)
        val user = ProvisionedUser("gone@example.org", "x")

        // Act
        testSubject.deleteUser(user)

        // Assert
        assertThat(webAdmin.requests).containsExactly(
            FakeWebAdmin.Request("DELETE", "/users/gone@example.org/mailboxes", ""),
            FakeWebAdmin.Request("DELETE", "/users/gone@example.org", ""),
        )
    }

    @Test
    fun `WebAdmin errors name the request but not the password`() {
        // Arrange
        webAdmin.failWith = 409
        val testSubject = JamesTestMailServer(config.copy(domain = "example.org"))

        // Act & Assert
        assertFailure { testSubject.createUser("x", "top-secret") }
            .isInstanceOf<IOException>()
            .all {
                messageContains("James WebAdmin PUT /domains/example.org failed: HTTP 409")
                transform { it.message.orEmpty() }.doesNotContain("top-secret")
            }
    }

    @Test
    fun `capabilities come from the post-login IMAP CAPABILITY of a throwaway user`() {
        // Arrange
        val testSubject = JamesTestMailServer(config)

        // Act
        val capabilities = testSubject.capabilities

        // Assert
        assertThat(capabilities).isEqualTo(
            setOf(ServerCapability.MOVE, ServerCapability.UIDPLUS, ServerCapability.IDLE),
        )
        assertThat(webAdmin.requests.map { it.method }).containsExactly("PUT", "PUT", "HEAD", "DELETE", "DELETE")
        assertThat(imapServer.users.keys.firstOrNull()).isNull()
    }

    @Test
    fun `requires an admin URL`() {
        assertFailure { JamesTestMailServer(config.copy(adminUrl = null)) }
            .isInstanceOf<IllegalArgumentException>()
            .messageContains("testserver.admin")
    }
}

/** A James WebAdmin stand-in that records requests and mirrors user changes into a [FakeImapServer]. */
class FakeWebAdmin(private val imapServer: FakeImapServer) : AutoCloseable {
    data class Request(val method: String, val path: String, val body: String)

    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var failWith: Int? = null

    /** How many of the next user creations answer 204 but aren't kept, like James' store under concurrent writes. */
    @Volatile
    var loseUserCreations = 0

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val path = exchange.requestURI.rawPath
            requests += Request(exchange.requestMethod, path, body)
            val status = failWith ?: handle(exchange.requestMethod, path, body)
            val response = if (status >= 400) "{\"message\":\"fake error\"}".toByteArray() else ByteArray(0)
            exchange.sendResponseHeaders(status, if (response.isEmpty()) -1 else response.size.toLong())
            if (response.isNotEmpty()) exchange.responseBody.write(response)
            exchange.close()
        }
        start()
    }

    val url: String = "http://127.0.0.1:${server.address.port}"

    private fun handle(method: String, path: String, body: String): Int {
        val user = path.removePrefix("/users/")
        return when {
            method == "PUT" && path.startsWith("/users/") -> {
                if (loseUserCreations > 0) {
                    loseUserCreations--
                } else {
                    imapServer.users[user] = Regex("\"password\":\"(.*)\"}").find(body)!!.groupValues[1]
                }
                204
            }

            method == "HEAD" && path.startsWith("/users/") -> if (user in imapServer.users) 200 else 404

            method == "DELETE" && path.endsWith("/mailboxes") -> 204

            method == "DELETE" && path.startsWith("/users/") -> if (imapServer.users.remove(user) != null) 204 else 404

            else -> 204
        }
    }

    override fun close() {
        server.stop(0)
    }
}
