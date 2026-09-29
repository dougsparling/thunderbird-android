package net.thunderbird.mail.testserver.proxy

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

class FaultProxyTest {
    private val literal = ByteArray(LITERAL_SIZE) { it.toByte() }
    private val server = FakeImapServer { command ->
        when {
            command.name == "UID FETCH" && command.raw.contains("BODY[]") -> {
                "* 1 FETCH (UID 1 BODY[] {$LITERAL_SIZE}\r\n".toByteArray() + literal +
                    ")\r\n${command.tag} OK UID FETCH done\r\n".toByteArray()
            }

            command.name == "EXPUNGE" -> "* 3 EXPUNGE\r\n${command.tag} OK EXPUNGE done\r\n".toByteArray()

            else -> null
        }
    }
    private val testSubject = FaultProxy.start("127.0.0.1", server.port)
    private val clients = mutableListOf<TestClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        testSubject.close()
        server.close()
    }

    @Test
    fun `forwards an IMAP session unchanged and records a readable transcript`() {
        val client = connect()

        val login = client.command("a1", "LOGIN alice s3cret")
        client.send("a2 UID FETCH 1 (BODY[])\r\n")
        val fetchLine = client.readLine()
        val body = client.readExactly(LITERAL_SIZE)
        val rest = listOf(client.readLine(), client.readLine())
        client.command("a3", "LOGOUT")
        client.close()

        assertThat(login).containsExactly("a1 OK LOGIN done")
        assertThat(fetchLine).isEqualTo("* 1 FETCH (UID 1 BODY[] {$LITERAL_SIZE}")
        assertThat(body.contentEquals(literal)).isTrue()
        assertThat(rest).containsExactly(")", "a2 OK UID FETCH done")
        assertThat(server.commands[0].raw).isEqualTo("a1 LOGIN alice s3cret")
        val transcript = awaitTranscript("closed: both sides closed")
        assertThat(transcript).contains("[c1] ** connected from client port")
        assertThat(transcript).contains("[c1] S: * OK fake ready")
        assertThat(transcript).contains("[c1] C: a1 LOGIN [redacted]")
        assertThat(transcript).contains("[c1] C: a2 UID FETCH 1 (BODY[])")
        assertThat(transcript).contains("[c1] S: [literal 20480 bytes] \\x00\\x01\\x02\\x03")
        assertThat(transcript).contains("... [+20280 more bytes]")
        assertThat(transcript).contains("[c1] S: )")
        assertThat(transcript).contains("[c1] S: a3 OK LOGOUT done")
        assertThat(transcript).doesNotContain("alice")
        assertThat(transcript).doesNotContain("s3cret")
    }

    @Test
    fun `passes arbitrary binary data through byte for byte`() {
        EchoServer().use { echo ->
            FaultProxy.start("127.0.0.1", echo.port).use { proxy ->
                val random = Random(7)
                val data = random.nextBytes(100_000) + "a1 APPEND x {5}\r\n12345\r\n".toByteArray() +
                    random.nextBytes(100_000)
                val client = TestClient(proxy.port).also { clients += it }

                val writer = Thread {
                    var position = 0
                    while (position < data.size) {
                        val count = minOf(random.nextInt(1, 5_000), data.size - position)
                        client.send(data.copyOfRange(position, position + count))
                        position += count
                    }
                }.apply { start() }
                val echoed = client.readExactly(data.size)
                writer.join()

                assertThat(echoed.contentEquals(data)).isTrue()
            }
        }
    }

    @Test
    fun `redacts AUTHENTICATE data`() {
        val client = connect()

        client.send("a1 AUTHENTICATE PLAIN\r\n")
        val challenge = client.readLine()
        client.send("AGFsaWNlAHMzY3JldA==\r\n")
        val result = client.readLine()
        client.command("a2", "NOOP")

        assertThat(challenge).isEqualTo("+ ")
        assertThat(result).isEqualTo("a1 OK AUTHENTICATE done")
        assertThat(server.authenticationData).containsExactly("AGFsaWNlAHMzY3JldA==")
        val transcript = testSubject.transcript()
        assertThat(transcript).contains("C: a1 AUTHENTICATE PLAIN\n")
        assertThat(transcript).contains("C: [redacted] (authentication exchange)")
        assertThat(transcript).contains("C: a2 NOOP")
        assertThat(transcript).doesNotContain("AGFsaWNlAHMzY3JldA==")
    }

    @Test
    fun `beforeServerSees holds the command and resets the connection`() {
        testSubject.apply(networkRules { imap.onCommand("uid fetch").beforeServerSees { reset() } })
        val client = connect()

        client.send("a1 UID FETCH 1 (FLAGS)\r\n")

        assertFailure { client.readLine() }.isInstanceOf<SocketException>()
        assertThat(server.commandNames()).isEmpty()
        assertThat(testSubject.transcript()).contains(
            "[c1] !! reset (rule: onCommand UID FETCH beforeServerSees) - held before the server saw it",
        )
    }

    @Test
    fun `afterServerResponds lets the server apply the command but withholds the response`() {
        testSubject.apply(networkRules { imap.onCommand("UID STORE").afterServerResponds { disconnect() } })
        val client = connect()

        client.send("a1 UID STORE 1 +FLAGS (\\Seen)\r\n")
        val response = client.readLine()

        assertThat(response).isNull()
        assertThat(server.commandNames()).containsExactly("UID STORE")
        val transcript = testSubject.transcript()
        assertThat(transcript).contains(
            "[c1] S: a1 OK UID STORE done\n",
        )
        assertThat(transcript).contains(
            "[c1] !! disconnect (rule: onCommand UID STORE afterServerResponds) - server responded; " +
                "response not delivered to client",
        )
    }

    @Test
    fun `rule counts are consumed across connections`() {
        testSubject.apply(networkRules { imap.onCommand("NOOP").beforeServerSees { disconnect() }.times(2) })

        val results = List(3) { index ->
            val client = connect()
            client.send("a$index NOOP\r\n")
            client.readLine()
        }

        assertThat(results).containsExactly(null, null, "a2 OK NOOP done")
    }

    @Test
    fun `commands inside literals do not trigger rules`() {
        testSubject.apply(networkRules { imap.onCommand("UID STORE").beforeServerSees { disconnect() } })
        val client = connect()
        val message = "a9 UID STORE 1 +FLAGS (\\Deleted)\r\n"

        client.send("a1 APPEND INBOX {${message.length}}\r\n")
        val continuation = client.readLine()
        client.send("$message\r\n")
        val result = client.readLine()

        assertThat(continuation).isEqualTo("+ go ahead")
        assertThat(result).isEqualTo("a1 OK APPEND done")
        assertThat(server.commands.single().raw).isEqualTo("a1 APPEND INBOX {${message.length}}\r\n$message")
    }

    @Test
    fun `onResponse delay holds the matching line`() {
        testSubject.apply(
            networkRules {
                imap.onResponse("3 EXPUNGE") { it.startsWith("* 3 EXPUNGE") }.then { delay(DELAY) }
            },
        )
        val client = connect()

        val response: List<String>
        val elapsed = measureTime { response = client.command("a1", "EXPUNGE") }

        assertThat(response).containsExactly("* 3 EXPUNGE", "a1 OK EXPUNGE done")
        assertThat(elapsed).isGreaterThanOrEqualTo(DELAY)
        assertThat(testSubject.transcript()).contains(
            "[c1] !! delay 300ms (rule: onResponse 3 EXPUNGE) - before forwarding the line above",
        )
    }

    @Test
    fun `onConnect refuses the numbered connection only`() {
        testSubject.apply(networkRules { onConnect(2) { refuse() } })

        val first = TestClient(testSubject.port).also { clients += it }
        val firstGreeting = first.readLine()
        val second = TestClient(testSubject.port).also { clients += it }
        assertFailure { second.readLine() }.isInstanceOf<SocketException>()
        val third = TestClient(testSubject.port).also { clients += it }
        val thirdGreeting = third.readLine()

        assertThat(firstGreeting).isEqualTo("* OK fake ready")
        assertThat(thirdGreeting).isEqualTo("* OK fake ready")
        assertThat(server.connectionCount).isEqualTo(2)
        assertThat(testSubject.transcript()).contains("[c2] !! refuse (rule: onConnect #2)")
    }

    @Test
    fun `refuseConnections simulates offline until rules change`() {
        testSubject.apply(networkRules { refuseConnections() })

        val offline = TestClient(testSubject.port).also { clients += it }
        assertFailure { offline.readLine() }.isInstanceOf<SocketException>()
        testSubject.apply(NetworkRules.NONE)
        val online = TestClient(testSubject.port).also { clients += it }

        assertThat(online.readLine()).isEqualTo("* OK fake ready")
        assertThat(testSubject.transcript()).contains("[c1] !! refuse (rule: refuseConnections)")
    }

    @Test
    fun `afterBytes delivers exactly the given number of bytes`() {
        testSubject.apply(networkRules { afterBytes(10, Direction.DOWNSTREAM) { disconnect() } })
        val client = TestClient(testSubject.port).also { clients += it }

        val received = client.readToEnd()

        assertThat(String(received)).isEqualTo("* OK fake ")
        assertThat(testSubject.transcript()).contains(
            "!! disconnect (rule: afterBytes 10 DOWNSTREAM) - after 10 bytes to client",
        )
    }

    @Test
    fun `latency delays both directions`() {
        val client = connect()
        testSubject.apply(networkRules { latency(DELAY) })

        val elapsed = measureTime { client.command("a1", "NOOP") }

        assertThat(elapsed).isGreaterThanOrEqualTo(DELAY * 2)
    }

    @Test
    fun `throttle limits throughput without changing data`() {
        val client = connect()
        testSubject.apply(networkRules { throttle(bytesPerSecond = 100_000) })

        val body: ByteArray
        val elapsed = measureTime {
            client.send("a1 UID FETCH 1 (BODY[])\r\n")
            client.readLine()
            body = client.readExactly(LITERAL_SIZE)
        }

        assertThat(body.contentEquals(literal)).isTrue()
        assertThat(elapsed).isGreaterThanOrEqualTo(150.milliseconds)
    }

    @Test
    fun `stall stops forwarding until the proxy is closed`() {
        testSubject.apply(networkRules { imap.onCommand("NOOP").beforeServerSees { stall() } })
        val client = TestClient(testSubject.port, readTimeoutMs = 300).also { clients += it }
        client.readLine()

        client.send("a1 NOOP\r\n")

        assertFailure { client.readLine() }.isInstanceOf<SocketTimeoutException>()
        val closeTime = measureTime { testSubject.close() }
        assertThat(closeTime).isLessThan(2_000.milliseconds)
        assertThat(server.commandNames()).isEmpty()
    }

    @Test
    fun `apply replaces the rules for connections that are already open`() {
        val client = connect()
        val before = client.command("a1", "NOOP")

        testSubject.apply(networkRules { imap.onCommand("NOOP").beforeServerSees { disconnect() } })
        client.send("a2 NOOP\r\n")

        assertThat(before).containsExactly("a1 OK NOOP done")
        assertThat(client.readLine()).isNull()
    }

    @Test
    fun `during applies rules for the block and restores the previous rules`() {
        val previous = networkRules { latency(1.milliseconds) }
        testSubject.apply(previous)

        val inside = testSubject.during(networkRules { imap.onCommand("NOOP").beforeServerSees { disconnect() } }) {
            val client = connect()
            client.send("a1 NOOP\r\n")
            client.readLine()
        }
        val after = connect().command("a2", "NOOP")

        assertThat(inside).isNull()
        assertThat(after).containsExactly("a2 OK NOOP done")
        assertThat(testSubject.activeRules).isEqualTo(previous)
    }

    @Test
    fun `forwards unterminated non-IMAP prompts`() {
        FakeImapServer(greeting = "login: ").use { promptServer ->
            FaultProxy.start("127.0.0.1", promptServer.port).use { proxy ->
                val client = TestClient(proxy.port).also { clients += it }

                val prompt = String(client.readExactly(7))

                assertThat(prompt).isEqualTo("login: ")
                assertThat(proxy.transcript()).contains("S: login:  [no line terminator yet]")
            }
        }
    }

    @Test
    fun `reports an unavailable upstream`() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        FaultProxy.start("127.0.0.1", unusedPort).use { proxy ->
            val client = TestClient(proxy.port).also { clients += it }

            val result = runCatching { client.readLine() }

            assertThat(result.getOrNull()).isNull()
            assertThat(proxy.transcript()).contains("!! upstream connect to 127.0.0.1:$unusedPort failed")
        }
    }

    @Test
    fun `close ends connections, frees the port and stops all threads`() {
        val first = connect()
        val second = connect()

        testSubject.close()

        assertThat(first.readLine()).isNull()
        assertThat(second.readLine()).isNull()
        assertFailure { TestClient(testSubject.port) }.isInstanceOf<ConnectException>()
        val prefix = "${SocketFaultProxy.THREAD_NAME_PREFIX}${testSubject.port}-"
        assertThat(Thread.getAllStackTraces().keys.filter { it.name.startsWith(prefix) }).isEmpty()
        assertThat(testSubject.transcript()).contains("closed: proxy closed")
    }

    @Test
    fun `transcript starts with a header`() {
        assertThat(testSubject.transcript()).startsWith("Fault proxy 127.0.0.1:${testSubject.port} -> 127.0.0.1:")
    }

    private fun connect(): TestClient {
        val client = TestClient(testSubject.port).also { clients += it }
        assertThat(client.readLine()).isEqualTo("* OK fake ready")
        return client
    }

    private fun awaitTranscript(text: String): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val transcript = testSubject.transcript()
            if (text in transcript) return transcript
            Thread.onSpinWait()
        }
        throw AssertionError("Transcript does not contain '$text':\n${testSubject.transcript()}")
    }

    private companion object {
        const val LITERAL_SIZE = 20480
        val DELAY = 300.milliseconds
    }
}
