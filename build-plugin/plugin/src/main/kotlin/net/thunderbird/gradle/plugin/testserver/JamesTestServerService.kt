package net.thunderbird.gradle.plugin.testserver

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Runs an Apache James memory server for scenario tests, shared by all test tasks of one build.
 *
 * Nothing happens until a test task asks for [endpoint], so builds that don't run scenario tests never resolve James,
 * never start a process and never touch the network. The first call:
 *
 * 1. stops a server left behind by a crashed earlier build (found via its pid file),
 * 2. copies the configuration templates into a fresh working directory, filling in free ports and the mail domain,
 * 3. launches James in its own JVM,
 * 4. waits until IMAP greets and the WebAdmin health check passes, and
 * 5. makes sure the test domain exists.
 *
 * Gradle calls [close] when the build finishes (also when it fails or is cancelled), which stops the process. The
 * working directory, including `james.log`, is kept for diagnostics and wiped at the next start.
 */
abstract class JamesTestServerService : BuildService<JamesTestServerService.Params>, AutoCloseable {

    interface Params : BuildServiceParameters {
        /** James and its runtime dependencies. */
        val classpath: ConfigurableFileCollection

        /** Configuration templates, copied to `<workDirectory>/conf`. */
        val configDirectory: DirectoryProperty

        val workDirectory: DirectoryProperty

        val domain: Property<String>

        val startupTimeoutSeconds: Property<Int>
    }

    private val logger = Logging.getLogger(JamesTestServerService::class.java)
    private val lock = Any()
    private var running: RunningServer? = null
    private var startFailure: GradleException? = null

    /** Starts the server on first use and returns where it can be reached. Thread-safe. */
    fun endpoint(): TestServerEndpoint = synchronized(lock) {
        startFailure?.let { throw it }
        running?.let { return it.endpoint }

        try {
            start().also { running = it }.endpoint
        } catch (e: GradleException) {
            startFailure = e
            throw e
        }
    }

    override fun close() {
        synchronized(lock) {
            running?.stop()
            running = null
        }
    }

    /**
     * James runs on the Gradle daemon's own JVM, which `gradle/gradle-daemon-jvm.properties` pins to Java 21.
     *
     * A toolchain provider in [Params] can't be restored from the configuration cache (the toolchain service isn't
     * available then), so the daemon's `java.home` is used instead.
     */
    private fun daemonJavaExecutable(): File {
        val javaVersion = Runtime.version().feature()
        if (javaVersion < MIN_JAVA_VERSION) {
            throw GradleException(
                "Apache James needs Java $MIN_JAVA_VERSION or newer, but the Gradle daemon runs Java $javaVersion",
            )
        }
        val javaHome = File(System.getProperty("java.home"))
        val executableName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return File(javaHome, "bin/$executableName")
    }

    /**
     * The free ports are found by binding to port 0 and closing the socket again, so another process can take one
     * before James binds it. In that case James is started again on fresh ports, up to [MAX_START_ATTEMPTS] times.
     */
    private fun start(): RunningServer {
        val workDir = parameters.workDirectory.get().asFile
        val pidFile = File(workDir.parentFile, "${workDir.name}.pid")
        stopStaleServer(pidFile)

        var attempt = 1
        while (true) {
            try {
                return startOnce(workDir, pidFile)
            } catch (e: PortInUseException) {
                if (attempt == MAX_START_ATTEMPTS) throw e
                logger.lifecycle("${e.message}; starting Apache James again on other ports")
                attempt++
            }
        }
    }

    private fun startOnce(workDir: File, pidFile: File): RunningServer {
        workDir.deleteRecursively()
        val confDir = File(workDir, "conf").apply { mkdirs() }

        val domain = parameters.domain.get()
        val imapPort = findFreePort()
        val webAdminPort = findFreePort()
        copyConfiguration(
            source = parameters.configDirectory.get().asFile,
            target = confDir,
            replacements = mapOf(
                "@IMAP_PORT@" to imapPort.toString(),
                "@WEBADMIN_PORT@" to webAdminPort.toString(),
                "@DOMAIN@" to domain,
            ),
        )

        val logFile = File(workDir, "james.log")
        val argFile = File(workDir, "java.args")
        argFile.writeText(
            buildList {
                add("-cp")
                add(parameters.classpath.files.joinToString(File.pathSeparator) { it.absolutePath })
            }.joinToString("\n") { quoteArgFileValue(it) },
        )

        val command = listOf(
            daemonJavaExecutable().absolutePath,
            "-Xms64m",
            "-Xmx1g",
            PROCESS_MARKER,
            "-Dworking.directory=${workDir.absolutePath}",
            "-Dlogback.configurationFile=${File(confDir, "logback.xml").absolutePath}",
            "-Djava.net.preferIPv4Stack=true",
            // TODO(verify): James 3.9 on JDK 21 may need extra --add-opens flags; check james.log on first run.
            "@${argFile.absolutePath}",
            MAIN_CLASS,
        )

        logger.lifecycle("Starting Apache James test server (IMAP port $imapPort, WebAdmin port $webAdminPort)")
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        pidFile.writeText(process.pid().toString())

        val server = RunningServer(
            process = process,
            pidFile = pidFile,
            adminPort = webAdminPort,
            endpoint = TestServerEndpoint(
                imapHost = LOOPBACK,
                imapPort = imapPort,
                adminUrl = "http://$LOOPBACK:$webAdminPort",
                domain = domain,
            ),
        )

        try {
            awaitReady(server, logFile)
            ensureDomainExists(server.endpoint)
        } catch (e: GradleException) {
            server.stop()
            throw e
        }

        logger.lifecycle("Apache James test server is ready. Log: $logFile")
        return server
    }

    private fun awaitReady(server: RunningServer, logFile: File) {
        val timeoutMillis = TimeUnit.SECONDS.toMillis(parameters.startupTimeoutSeconds.get().toLong())
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastProblem = "not checked yet"

        while (System.currentTimeMillis() < deadline) {
            checkPortsBound(server, logFile)
            if (!server.process.isAlive) {
                throw GradleException(
                    "Apache James exited during startup with code ${server.process.exitValue()}.\n" +
                        "Last lines of $logFile:\n${tail(logFile)}",
                )
            }

            val imapProblem = checkImapGreeting(server.endpoint)
            val webAdminProblem = if (imapProblem == null) checkHealth(server.endpoint) else null
            if (imapProblem == null && webAdminProblem == null) return

            lastProblem = imapProblem ?: webAdminProblem.orEmpty()
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }

        throw GradleException(
            "Apache James didn't become ready within ${parameters.startupTimeoutSeconds.get()}s ($lastProblem).\n" +
                "Last lines of $logFile:\n${tail(logFile)}",
        )
    }

    /**
     * Throws [PortInUseException] if James logged that it couldn't bind one of its ports. James may keep running in
     * that case, so this doesn't wait for it to exit.
     */
    private fun checkPortsBound(server: RunningServer, logFile: File) {
        if (!logFile.exists() || logFile.readLines().none { BIND_FAILURE in it }) return
        throw PortInUseException(
            "Apache James couldn't bind IMAP port ${server.endpoint.imapPort} or WebAdmin port " +
                "${server.adminPort}; another process took it",
        )
    }

    private fun checkImapGreeting(endpoint: TestServerEndpoint): String? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.imapHost, endpoint.imapPort), SOCKET_TIMEOUT_MILLIS)
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                val greeting = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                if (greeting != null && greeting.startsWith("* OK")) null else "unexpected IMAP greeting: $greeting"
            }
        } catch (e: IOException) {
            "IMAP not reachable: ${e.message}"
        }
    }

    private fun checkHealth(endpoint: TestServerEndpoint): String? {
        // TODO(verify): /healthcheck returns 200 once all James components are healthy.
        val (status, body) = http("GET", "${endpoint.adminUrl}/healthcheck")
        return if (status == HTTP_OK) null else "WebAdmin health check returned $status: $body"
    }

    private fun ensureDomainExists(endpoint: TestServerEndpoint) {
        val url = "${endpoint.adminUrl}/domains/${endpoint.domain}"
        val (status, body) = http("PUT", url)
        if (status in HTTP_SUCCESS) return

        // Creating an existing domain may be rejected; that's fine as long as it exists.
        val (existsStatus, _) = http("GET", url)
        if (existsStatus !in HTTP_SUCCESS) {
            throw GradleException("Couldn't create test domain ${endpoint.domain} via WebAdmin ($status): $body")
        }
    }

    private fun http(method: String, url: String): Pair<Int, String> {
        return try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = SOCKET_TIMEOUT_MILLIS
                connection.readTimeout = SOCKET_TIMEOUT_MILLIS
                val status = connection.responseCode
                val stream = if (status >= HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
                status to (stream?.bufferedReader()?.use { it.readText() }.orEmpty()).take(MAX_BODY_CHARS)
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            -1 to "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun stopStaleServer(pidFile: File) {
        if (!pidFile.exists()) return

        val pid = pidFile.readText().trim().toLongOrNull()
        pidFile.delete()
        if (pid == null) return

        ProcessHandle.of(pid).ifPresent { handle ->
            val info = handle.info()
            val commandLine =
                info.commandLine().orElse("") + " " + info.arguments().orElse(emptyArray()).joinToString(" ")
            if (PROCESS_MARKER in commandLine) {
                logger.lifecycle("Stopping Apache James test server left behind by an earlier build (pid $pid)")
                stopProcess(handle)
            } else if (commandLine.isBlank()) {
                logger.warn(
                    "Stale James pid file points to process $pid, but its command line can't be read; leaving it",
                )
            }
        }
    }

    private fun copyConfiguration(source: File, target: File, replacements: Map<String, String>) {
        if (!source.isDirectory) throw GradleException("James configuration directory not found: $source")

        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val destination = File(target, file.relativeTo(source).path)
            destination.parentFile.mkdirs()
            val content = replacements.entries.fold(file.readText()) { text, (token, value) ->
                text.replace(token, value)
            }
            destination.writeText(content)
        }
    }

    private inner class RunningServer(
        val process: Process,
        val pidFile: File,
        val adminPort: Int,
        val endpoint: TestServerEndpoint,
    ) {
        fun stop() {
            logger.lifecycle("Stopping Apache James test server")
            stopProcess(process.toHandle())
            pidFile.delete()
        }
    }

    private fun stopProcess(handle: ProcessHandle) {
        val descendants = handle.descendants().toList()
        handle.destroy()
        val exited = try {
            handle.onExit().get(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)
            true
        } catch (_: Exception) {
            false
        }
        if (!exited) {
            logger.warn("Apache James didn't stop within ${GRACEFUL_STOP_SECONDS}s; killing it")
            handle.destroyForcibly()
        }
        descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }

    private class PortInUseException(message: String) : GradleException(message)

    private companion object {
        const val MAIN_CLASS = "org.apache.james.MemoryJamesServerMain"

        // TODO(verify): James 3.9 is built for Java 21.
        const val MIN_JAVA_VERSION = 21

        /** Marks our process so a stale one can be recognised safely from its command line. */
        const val PROCESS_MARKER = "-Dthunderbird.testserver=james"

        const val MAX_START_ATTEMPTS = 3

        // TODO(verify): IMAP (Netty) and WebAdmin (Jetty) both log this BindException message when the port is taken.
        /** Text of the `java.net.BindException` James logs when a port is already taken. */
        const val BIND_FAILURE = "Address already in use"

        const val POLL_INTERVAL_MILLIS = 500L
        const val SOCKET_TIMEOUT_MILLIS = 2_000
        const val GRACEFUL_STOP_SECONDS = 20L
        const val MAX_BODY_CHARS = 500
        const val TAIL_LINES = 60
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        val HTTP_SUCCESS = 200..299

        const val LOOPBACK = "127.0.0.1"

        fun findFreePort(): Int = ServerSocket(0, 0, InetAddress.getByName(LOOPBACK)).use { it.localPort }

        fun tail(file: File): String =
            if (file.exists()) file.readLines().takeLast(TAIL_LINES).joinToString("\n") else "(no log written)"

        /** Quotes a value for a Java `@argfile`, where backslashes and quotes are escape characters. */
        fun quoteArgFileValue(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/** Where the running test server can be reached. */
data class TestServerEndpoint(
    val imapHost: String,
    val imapPort: Int,
    val adminUrl: String,
    val domain: String,
)
