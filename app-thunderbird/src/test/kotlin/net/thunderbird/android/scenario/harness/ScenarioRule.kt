package net.thunderbird.android.scenario.harness

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

    /** The fault proxy between the app and the server. Its transcript is printed when the scenario fails. */
    val proxy: FaultProxy

    /** Replaces the proxy's active network rules, e.g. `network { imap.onCommand("UID STORE")... }`. */
    fun network(block: NetworkRulesBuilder.() -> Unit)
}

class ScenarioClient internal constructor(
    private val driverProvider: () -> ScenarioDriver,
    private val proxyProvider: () -> FaultProxy,
) {
    /**
     * Adds an account for [user] to the app, connecting through the fault proxy. Pass a different [password] to set
     * the account up with wrong credentials, and [checkIntervalMinutes] to have the app schedule periodic mail sync
     * (see [ScenarioDriver.periodicSyncDue]); without it the account never syncs in the background.
     */
    fun account(
        user: ProvisionedUser,
        password: String = user.password,
        checkIntervalMinutes: Int? = null,
    ): ClientAccount {
        return driverProvider().addAccount(
            AccountSpec(
                email = user.username,
                imapHost = PROXY_HOST,
                imapPort = proxyProvider().port,
                username = user.username,
                password = password,
                checkIntervalMinutes = checkIntervalMinutes,
            ),
        )
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
        private val driverDelegate = lazy { LegacyScenarioDriver(GlobalContext.get()) }

        override val server: ScenarioServer by serverDelegate
        override val proxy: FaultProxy by proxyDelegate
        override val driver: ScenarioDriver by driverDelegate
        override val client = ScenarioClient(driverProvider = { driver }, proxyProvider = { proxy })

        override fun network(block: NetworkRulesBuilder.() -> Unit) {
            proxy.apply(networkRules(block))
        }

        fun printDiagnostics(description: Description) {
            if (!proxyDelegate.isInitialized()) return

            System.err.println(
                buildString {
                    appendLine("==== Scenario failed: ${description.displayName} ====")
                    appendLine("Active network rules: ${proxy.activeRules.describe()}")
                    appendLine("---- Proxy transcript (app <-> test mail server) ----")
                    appendLine(proxy.transcript())
                    appendLine("---- End of proxy transcript ----")
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
            if (proxyDelegate.isInitialized()) attempt { proxy.close() }
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
