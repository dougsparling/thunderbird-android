package net.thunderbird.android.scenario.harness

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import net.thunderbird.mail.testserver.provision.ProvisionedUser
import net.thunderbird.mail.testserver.provision.TestServerConfig
import net.thunderbird.mail.testserver.proxy.FaultProxy
import net.thunderbird.mail.testserver.proxy.NetworkRulesBuilder
import net.thunderbird.mail.testserver.proxy.networkRules
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin

/** Everything a scenario works with. */
interface ScenarioScope {
    /** The test mail server: create users with seeded mailboxes, read their state back. */
    val server: ScenarioServer

    /** Adds accounts to the app, connected through [proxy]. */
    val client: ScenarioClient

    /** Drives the app and reads what the user sees. */
    val driver: ScenarioDriver

    /** The Android device the app runs on: permissions, time, background work, notifications. */
    val device: ScenarioDevice

    /**
     * The fault proxy between the app and the server's IMAP port. Its transcript is printed when the scenario fails.
     */
    val proxy: FaultProxy

    /** The fault proxy between the app and the server's SMTP port, used by every account to send mail. */
    val smtpProxy: FaultProxy

    /** The fault proxy between the app and the server's POP3 port, used by POP3 accounts. */
    val pop3Proxy: FaultProxy

    /** Replaces the IMAP proxy's active network rules, e.g. `network { imap.onCommand("UID STORE")... }`. */
    fun network(block: NetworkRulesBuilder.() -> Unit)

    /** Replaces the SMTP proxy's active network rules, e.g. `smtpNetwork { refuseConnections() }`. */
    fun smtpNetwork(block: NetworkRulesBuilder.() -> Unit)

    /**
     * The device loses its network: the proxies refuse new connections and reset the open ones, and the app is told
     * the network is gone ([ScenarioDevice.setOnline]). Other network rules stay active, but their use counts
     * (`once()`, `times(n)`) start over.
     */
    fun goOffline()

    /**
     * The device is back online: the proxies accept connections again and the app is told the network is available.
     */
    fun goOnline()

    /**
     * Waits until the app is listening for new mail: one of its connections has sent IDLE and the server accepted it.
     * Read from the proxy transcript, so it doesn't depend on how the app implements push.
     */
    fun awaitAppListening(timeout: Duration = DEFAULT_WAIT)

    /**
     * Retries [block] until it passes or [timeout] runs out, letting the app finish its work in between. For effects
     * the server starts, such as pushed mail, which the app handles in its own time.
     */
    fun eventually(timeout: Duration = DEFAULT_WAIT, block: () -> Unit)

    companion object {
        val DEFAULT_WAIT: Duration = 20.seconds
    }
}

class ScenarioClient internal constructor(
    private val driverProvider: () -> ScenarioDriver,
    private val proxyProvider: (MailProtocol) -> FaultProxy,
    private val smtpProxyProvider: () -> FaultProxy,
    private val onFirstAccount: () -> Unit,
) {
    /**
     * Adds an account for [user] to the app, connecting through the fault proxies. Pass a different [password] (or
     * [smtpPassword]) to set the account up with wrong credentials, and [checkIntervalMinutes] to have the app
     * schedule periodic mail sync (it runs as [ScenarioDevice.advanceTime] lets time pass); without it the account
     * never syncs in the background. With [notifyNewMail] the user gets notifications for new inbox mail found by
     * background sync or push. [settings] are changed in the account settings right after setup, i.e. after the
     * first mail check.
     */
    fun account(
        user: ProvisionedUser,
        password: String = user.password,
        smtpPassword: String = user.password,
        protocol: MailProtocol = MailProtocol.IMAP,
        checkIntervalMinutes: Int? = null,
        notifyNewMail: Boolean = false,
        settings: ClientAccountSettings? = null,
    ): ClientAccount {
        onFirstAccount()
        val driver = driverProvider()
        val account = driver.addAccount(
            AccountSpec(
                email = user.username,
                protocol = protocol,
                incomingHost = PROXY_HOST,
                incomingPort = proxyProvider(protocol).port,
                smtpHost = PROXY_HOST,
                smtpPort = smtpProxyProvider().port,
                username = user.username,
                password = password,
                smtpPassword = smtpPassword,
                checkIntervalMinutes = checkIntervalMinutes,
                notifyNewMail = notifyNewMail,
            ),
        )
        settings?.let { driver.changeSettings(account, it) }
        return account
    }

    private companion object {
        const val PROXY_HOST = "127.0.0.1"
    }
}

/**
 * Sets up and tears down one scenario, see [ScenarioTest].
 *
 * The server connection, proxy and driver are created on first use. At the end the driver and proxy are closed, the
 * scenario's server users are deleted (best effort) and Koin is stopped. When the scenario fails, the proxy transcript
 * of everything the app said to the server is printed.
 */
class ScenarioRule : TestRule {
    private var session: Session? = null

    fun run(block: ScenarioScope.() -> Unit) {
        val current = checkNotNull(session) { "scenario { } can only be used while a test is running" }
        current.block()
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            ScenarioJvmGuard.claim(description.displayName)

            val current = Session(nameHint = "${description.testClass?.simpleName}-${description.methodName}")
            session = current
            var failure: Throwable? = null
            try {
                base.evaluate()
            } catch (e: Throwable) {
                failure = e
                current.printDiagnostics(description)
                throw e
            } finally {
                session = null
                current.close(failure)
            }
        }
    }

    private class Session(private val nameHint: String) : ScenarioScope {
        private val config: TestServerConfig by lazy { TestServerConfig.fromSystemProperties() }

        private val serverDelegate = lazy { ScenarioServer(config, nameHint) }
        private val proxyDelegate = lazy { FaultProxy.start(config.imapHost, config.imapPort) }
        private val smtpProxyDelegate = lazy {
            val smtp = checkNotNull(config.smtp) { "The test mail server has no SMTP endpoint (testserver.smtp)" }
            FaultProxy.start(smtp.host, smtp.port)
        }
        private val pop3ProxyDelegate = lazy {
            val pop3 = checkNotNull(config.pop3) { "The test mail server has no POP3 endpoint (testserver.pop3)" }
            FaultProxy.start(pop3.host, pop3.port)
        }
        private val proxyDelegates = listOf(proxyDelegate, smtpProxyDelegate, pop3ProxyDelegate)

        // The device replaces platform parts (WorkManager) the app caches, so it's set up before the app is used.
        private val deviceDelegate = lazy { ScenarioDevice(GlobalContext.get(), awaitAppIdle = { driver.awaitIdle() }) }
        private val driverDelegate = lazy {
            deviceDelegate.value
            LegacyScenarioDriver(GlobalContext.get())
        }

        override val server: ScenarioServer by serverDelegate
        override val proxy: FaultProxy by proxyDelegate
        override val smtpProxy: FaultProxy by smtpProxyDelegate
        override val pop3Proxy: FaultProxy by pop3ProxyDelegate
        override val driver: ScenarioDriver by driverDelegate
        override val device: ScenarioDevice by deviceDelegate
        override val client = ScenarioClient(
            driverProvider = { driver },
            proxyProvider = { protocol ->
                when (protocol) {
                    MailProtocol.IMAP -> proxy
                    MailProtocol.POP3 -> pop3Proxy
                }
            },
            smtpProxyProvider = { smtpProxy },
            onFirstAccount = { device.markAppInUse() },
        )

        /** The proxies the scenario has used so far. */
        private val startedProxies: List<FaultProxy>
            get() = proxyDelegates.filter { it.isInitialized() }.map { it.value }

        override fun network(block: NetworkRulesBuilder.() -> Unit) {
            proxy.apply(networkRules(block))
        }

        override fun smtpNetwork(block: NetworkRulesBuilder.() -> Unit) {
            smtpProxy.apply(networkRules(block))
        }

        override fun goOffline() {
            startedProxies.forEach { proxy ->
                proxy.apply(proxy.activeRules.copy(refuseConnections = true))
                proxy.disconnectAll(reset = true)
            }
            device.setOnline(false)
        }

        override fun goOnline() {
            startedProxies.forEach { proxy -> proxy.apply(proxy.activeRules.copy(refuseConnections = false)) }
            device.setOnline(true)
        }

        override fun awaitAppListening(timeout: Duration) {
            waitUntil(timeout, what = "the app to send IDLE and the server to accept it") {
                isIdling(proxy.transcript())
            }
        }

        override fun eventually(timeout: Duration, block: () -> Unit) {
            val deadline = TimeSource.Monotonic.markNow() + timeout
            while (true) {
                try {
                    block()
                    return
                } catch (e: AssertionError) {
                    if (deadline.hasPassedNow()) throw e
                    device.settle()
                    Thread.sleep(POLL_INTERVAL.inWholeMilliseconds)
                }
            }
        }

        private fun waitUntil(timeout: Duration, what: String, condition: () -> Boolean) {
            val deadline = TimeSource.Monotonic.markNow() + timeout
            while (!condition()) {
                check(!deadline.hasPassedNow()) { "Timed out after $timeout waiting for $what" }
                device.settle()
                Thread.sleep(POLL_INTERVAL.inWholeMilliseconds)
            }
        }

        fun printDiagnostics(description: Description) {
            val named = listOf("IMAP" to proxyDelegate, "SMTP" to smtpProxyDelegate, "POP3" to pop3ProxyDelegate)
                .filter { (_, delegate) -> delegate.isInitialized() }
            if (named.isEmpty()) return

            System.err.println(
                buildString {
                    appendLine("==== Scenario failed: ${description.displayName} ====")
                    for ((protocol, delegate) in named) {
                        val proxy = delegate.value
                        appendLine("Active $protocol network rules: ${proxy.activeRules.describe()}")
                        appendLine("---- $protocol proxy transcript (app <-> test mail server) ----")
                        appendLine(proxy.transcript())
                        appendLine("---- End of $protocol proxy transcript ----")
                    }
                },
            )
        }

        /** Tears everything down; problems are attached to [failure] or thrown if the scenario passed. */
        fun close(failure: Throwable?) {
            val problems = mutableListOf<Throwable>()
            fun attempt(block: () -> Unit) {
                runCatching(block).exceptionOrNull()?.let(problems::add)
            }

            if (driverDelegate.isInitialized()) attempt { driver.close() }
            if (deviceDelegate.isInitialized()) attempt { device.close() }
            startedProxies.forEach { proxy -> attempt { proxy.close() } }
            if (serverDelegate.isInitialized()) {
                // Leftover users only cost memory on the test server, so failing to delete them doesn't fail the test.
                runCatching { server.deleteUsers() }.exceptionOrNull()?.let { e ->
                    System.err.println("Warning: couldn't delete scenario users on the test server: $e")
                }
            }
            attempt { stopKoin() }

            if (failure != null) {
                problems.forEach(failure::addSuppressed)
            } else if (problems.isNotEmpty()) {
                val first = problems.first()
                problems.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }
}

private val POLL_INTERVAL = 100.milliseconds
private val TRANSCRIPT_LINE = Regex("""\[(c\d+)] ([CS]): (.*)""")
private val IDLE_COMMAND = Regex("""\S+ IDLE""", RegexOption.IGNORE_CASE)

/** True if, on some connection, the last client command was IDLE and the server has answered it with `+`. */
internal fun isIdling(transcript: String): Boolean {
    val idleSent = mutableSetOf<String>()
    val idling = mutableSetOf<String>()
    for (match in transcript.lineSequence().mapNotNull { TRANSCRIPT_LINE.find(it) }) {
        val (connection, direction, text) = match.destructured
        when {
            direction == "C" && IDLE_COMMAND.matches(text) -> idleSent += connection

            direction == "C" -> {
                idleSent -= connection
                idling -= connection
            }

            text.startsWith("+") && connection in idleSent -> idling += connection
        }
    }
    return idling.isNotEmpty()
}

/**
 * Makes sure only one scenario runs per test JVM.
 *
 * The app keeps Koin-injected dependencies in Kotlin objects (`Core`, `K9`) that are resolved once and can't be reset,
 * so a second app instance in the same JVM would silently use the first one's dependencies. The Gradle build runs
 * every scenario class in a fresh JVM (`forkEvery = 1`), which means one test method per scenario class.
 */
private object ScenarioJvmGuard {
    private var claimedBy: String? = null

    @Synchronized
    fun claim(testName: String) {
        val previous = claimedBy
        check(previous == null) {
            "Only one scenario can run per JVM, but '$testName' runs after '$previous'. Put each scenario in its own " +
                "test class with a single test method, and run scenarios through Gradle, which forks a JVM per class."
        }
        claimedBy = testName
    }
}
